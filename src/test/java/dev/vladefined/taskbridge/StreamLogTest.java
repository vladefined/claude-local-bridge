package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.vladefined.taskbridge.runner.StreamLog;

class StreamLogTest {

    final ObjectMapper json = new ObjectMapper();

    /** Shape of `claude -p --output-format stream-json --verbose`. */
    static final String STREAM = String.join("\n",
            "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\",\"model\":\"m\",\"tools\":[\"Read\"]}",
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Сначала посмотрю файл.\"},"
                    + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Read\",\"input\":{\"file_path\":\"E:\\\\pibench\\\\run.py\"}}]},\"session_id\":\"s-1\"}",
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"content\":\"import os\\nprint(1)\"}]}}",
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"Bash\",\"input\":{\"command\":\"python -m pytest tests\",\"description\":\"run tests\"}}]}}",
            "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t2\",\"is_error\":true,\"content\":[{\"type\":\"text\",\"text\":\"No module named pytest\"}]}]}}",
            "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Готово.\\nSUMMARY: Прочитал run.py.\"}]}}",
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":3,\"duration_ms\":4200,"
                    + "\"result\":\"Готово.\\nSUMMARY: Прочитал run.py.\",\"session_id\":\"s-1\",\"total_cost_usd\":0.0123}",
            "");

    Path write(Path dir, String s) throws Exception {
        Path p = dir.resolve("x.stdout.json");
        Files.writeString(p, s, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void finalResultComesFromTheResultEvent(@TempDir Path dir) throws Exception {
        StreamLog.Final f = StreamLog.findFinal(write(dir, STREAM), json);
        assertThat(f.result()).isEqualTo("Готово.\nSUMMARY: Прочитал run.py.");
        assertThat(f.sessionId()).isEqualTo("s-1");
        assertThat(f.isError()).isFalse();
    }

    @Test
    void onlyTheLastAnswerKeepsItsSummaryLine(@TempDir Path dir) throws Exception {
        String r1 = "{\"type\":\"result\",\"result\":\"Started in the background.\\n\\nSUMMARY: waiting\",\"session_id\":\"s\"}\n";
        String r2 = "{\"type\":\"result\",\"result\":\"done slept\\n\\nSUMMARY: slept 7 min\",\"session_id\":\"s\"}\n";
        StreamLog.Final f = StreamLog.findFinal(write(dir, r1 + r2), json);
        assertThat(f.result()).isEqualTo("Started in the background.\n\ndone slept\n\nSUMMARY: slept 7 min");
    }

    @Test
    void objectWithoutResultTypeIsNotAnAnswer(@TempDir Path dir) throws Exception {
        assertThat(StreamLog.findFinal(
                write(dir, "{\"result\":\"ok\",\"session_id\":\"s\",\"is_error\":false}\r\n"), json)).isNull();
    }

    @Test
    void noResultEventMeansNoFinal(@TempDir Path dir) throws Exception {
        String cut = STREAM.substring(0, STREAM.indexOf("{\"type\":\"result\""));
        assertThat(StreamLog.findFinal(write(dir, cut), json)).isNull();
    }

    @Test
    void progressListsToolCallsAndMessages(@TempDir Path dir) throws Exception {
        List<String> steps = StreamLog.recentSteps(write(dir, STREAM), 15, json);
        assertThat(steps).containsExactly(
                "Claude: Сначала посмотрю файл.",
                "→ Read(E:\\pibench\\run.py)",
                "→ Bash(python -m pytest tests)",
                "Claude: Готово. SUMMARY: Прочитал run.py.",
                "[end] done, turns 3, 4 s, $0.0123");
        assertThat(StreamLog.recentSteps(write(dir, STREAM), 2, json)).hasSize(2)
                .last().asString().startsWith("[end]");
    }

    @Test
    void lineStillBeingWrittenIsIgnored(@TempDir Path dir) throws Exception {
        String partial = STREAM.substring(0, STREAM.indexOf("{\"type\":\"result\"") + 20);
        List<String> steps = StreamLog.recentSteps(write(dir, partial), 15, json);
        assertThat(steps).last().asString().startsWith("Claude: Готово.");
    }

    @Test
    void transcriptHasMessagesToolsAndResults(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("x.log.txt");
        StreamLog.writeTranscript(write(dir, STREAM), out, json);
        String t = Files.readString(out, StandardCharsets.UTF_8);
        assertThat(t).contains("[start] session s-1", "Claude: Сначала посмотрю файл.",
                "→ Read(E:\\pibench\\run.py)", "  ← import os", "  ← error: No module named pytest",
                "[end] done");
    }

    @Test
    void snapshotGivesACursorAndPlacesDeliveredMessages(@TempDir Path dir) throws Exception {
        Path f = write(dir, STREAM);
        long afterRead = STREAM.indexOf("{\"type\":\"user\"");          // offset of the line after Read
        var s = StreamLog.snapshot(f, 15, json, List.of(new StreamLog.Insert(afterRead, "also lint")));
        assertThat(s.cursor()).isEqualTo(java.nio.file.Files.size(f));
        assertThat(s.steps()).containsSubsequence("→ Read(E:\\pibench\\run.py)",
                "✉ Message from the caller (approved): also lint", "→ Bash(python -m pytest tests)");
        // a half-written last line does not move the cursor
        Path g = write(dir, STREAM + "{\"type\":\"assist");
        assertThat(StreamLog.snapshot(g, 15, json, List.of()).cursor()).isEqualTo(STREAM.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void stepsSinceCountsOnlyWhatCameAfterTheCursor(@TempDir Path dir) throws Exception {
        Path f = write(dir, STREAM);
        long len = java.nio.file.Files.size(f);
        assertThat(StreamLog.stepsSince(f, len, json)).isZero();
        assertThat(StreamLog.stepsSince(f, 0, json)).isEqualTo(5);
        assertThat(StreamLog.stepsSince(f, len + 1, json)).isEqualTo(Integer.MAX_VALUE);
        assertThat(StreamLog.stepsSince(f, -5, json)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void severalAnswersAreJoined(@TempDir Path dir) throws Exception {
        String two = STREAM + "{\"type\":\"result\",\"is_error\":false,\"result\":\"late answer\",\"session_id\":\"s-1\"}\n";
        assertThat(StreamLog.findFinal(write(dir, two), json).result())
                .isEqualTo("Готово.\n\nlate answer");
    }

    @Test
    void missingFileHasNoSteps(@TempDir Path dir) throws Exception {
        assertThat(StreamLog.recentSteps(dir.resolve("none"), 5, json)).isEmpty();
    }
}

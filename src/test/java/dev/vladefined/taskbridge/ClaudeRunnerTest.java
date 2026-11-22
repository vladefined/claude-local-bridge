package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.vladefined.taskbridge.config.BridgeProperties;
import dev.vladefined.taskbridge.config.BridgeProperties.Folder;
import dev.vladefined.taskbridge.core.RunMode;
import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.TaskStatus;
import dev.vladefined.taskbridge.runner.ClaudeRunner;

class ClaudeRunnerTest {

    static final Folder FOLDER = new Folder("/tmp", List.of("Read", "Bash(git status:*)"), null);
    static final BridgeProperties PROPS = new BridgeProperties("0123456789abcdef0123456789abcdef",
            Duration.ofMinutes(5), Duration.ofMinutes(30), 3000, 3500, null, null,
            new BridgeProperties.Runner(List.of("claude"), Duration.ofMinutes(20), "outputs", Duration.ofDays(30), BridgeProperties.Runner.DEFAULT_SUMMARY_PROMPT), Map.of("x", FOLDER));
    final ClaudeRunner r = new ClaudeRunner(PROPS, new ObjectMapper());

    static Task task(RunMode mode, String session, String text) {
        return new Task("abc", "x", mode, session, text, "s", "0".repeat(64), TaskStatus.APPROVED, null,
                Instant.now(), null, null, null, null, null, null, false, null, null);
    }

    @Test
    void taskTextIsNeverACommandLineArgument() {
        String evil = "\" & del C:\\* & \"";
        List<String> cmd = r.buildCommand(task(RunMode.CONTINUE, null, evil), FOLDER);
        assertThat(cmd).doesNotContain(evil)
                .containsExactly("claude", "-p", "--output-format", "stream-json", "--verbose", "--continue",
                        "--append-system-prompt", BridgeProperties.Runner.DEFAULT_SUMMARY_PROMPT,
                        "--allowedTools", "Read,Bash(git status:*)");
    }

    @Test
    void summaryPromptCannotCarryCmdMetacharacters() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BridgeProperties.Runner(
                List.of("claude"), Duration.ofMinutes(1), "o", Duration.ZERO, "say \"hi\" & del"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resumePassesTheSessionId() {
        assertThat(r.buildCommand(task(RunMode.RESUME, "abc-123", "x"), FOLDER))
                .containsSequence("--resume", "abc-123");
    }

    @Test
    void missingFolderIsReported() {
        var res = new ClaudeRunner(PROPS, new ObjectMapper()).run(task(RunMode.NEW, null, "x"),
                new Folder("/definitely/not/here", List.of(), null));
        assertThat(res.ok()).isFalse();
        assertThat(res.text()).contains("does not exist");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void textArrivesThroughStdinAndJsonIsParsed(@TempDir Path dir) throws Exception {
        // A stand-in for `claude`: echoes stdin back inside Claude Code's JSON shape.
        Path fake = dir.resolve("fake-claude");
        Files.writeString(fake, """
                #!/bin/sh
                body=$(cat)
                printf '{"result":"got: %s","session_id":"s-42","is_error":false}' "$body"
                """);
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"));
        BridgeProperties props = new BridgeProperties(PROPS.hmacSecret(), PROPS.maxClockSkew(),
                PROPS.approvalTtl(), 3000, 3500, null, null,
                new BridgeProperties.Runner(List.of(fake.toString()), Duration.ofSeconds(10),
                        dir.resolve("out").toString(), Duration.ofDays(30), ""), PROPS.folders());
        var res = new ClaudeRunner(props, new ObjectMapper())
                .run(task(RunMode.NEW, null, "hello & goodbye"), new Folder(dir.toString(), List.of(), null));
        assertThat(res.ok()).isTrue();
        assertThat(res.text()).isEqualTo("got: hello & goodbye");
        assertThat(res.sessionId()).isEqualTo("s-42");
        // the full result is saved to <output-dir>/<task id>.txt
        // full result saved as <output-dir>/<folder>/<session id>/<task id>.txt, nothing left in _running
        Path session = dir.resolve("out").resolve("x").resolve("s-42").toAbsolutePath();
        assertThat(res.outputFile()).isEqualTo(session.resolve("abc.txt"));
        assertThat(session.resolve("abc.stdout.json")).exists();
        assertThat(dir.resolve("out").resolve("x").resolve("_running")).isEmptyDirectory();
        assertThat(Files.readString(res.outputFile())).isEqualTo("got: hello & goodbye");
    }

    @Test
    void sessionIdFromOutputCannotEscapeTheOutputFolder() {
        assertThat(ClaudeRunner.sessionDir("3f2c9a1e-1b2c-4d5e-8f90-1234567890ab"))
                .isEqualTo("3f2c9a1e-1b2c-4d5e-8f90-1234567890ab");
        assertThat(ClaudeRunner.sessionDir("..\\..\\Windows")).isEqualTo("_failed");
        assertThat(ClaudeRunner.sessionDir("../x")).isEqualTo("_failed");
        assertThat(ClaudeRunner.sessionDir(null)).isEqualTo("_failed");
    }
}

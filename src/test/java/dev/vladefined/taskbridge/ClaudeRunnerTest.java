package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
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
                .containsExactly("claude", "-p", "--output-format", "stream-json", "--verbose",
                        "--input-format", "stream-json", "--continue",
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

    static ClaudeRunner fakeRunner(Path dir, int steps, long stepMillis) {
        BridgeProperties props = new BridgeProperties(PROPS.hmacSecret(), PROPS.maxClockSkew(),
                PROPS.approvalTtl(), 3000, 3500, null, null,
                new BridgeProperties.Runner(FakeClaude.command(steps, stepMillis), Duration.ofSeconds(60),
                        dir.resolve("out").toString(), Duration.ofDays(30), ""), PROPS.folders());
        return new ClaudeRunner(props, new ObjectMapper());
    }

    @Test
    void textArrivesThroughStdinAndTheRunEndsAfterTheAnswer(@TempDir Path dir) throws Exception {
        // FakeClaude keeps reading stdin after its answer: the run only ends because the
        // runner closes stdin once it sees the result event.
        var res = fakeRunner(dir, 2, 50)
                .run(task(RunMode.NEW, null, "hello & goodbye"), new Folder(dir.toString(), List.of(), null));
        assertThat(res.ok()).isTrue();
        assertThat(res.text()).isEqualTo("done: hello & goodbye");
        assertThat(res.sessionId()).isEqualTo("fake-1");
        // full result saved as <output-dir>/<folder>/<session id>/<task id>.txt, nothing left in _running
        Path session = dir.resolve("out").resolve("x").resolve("fake-1").toAbsolutePath();
        assertThat(res.outputFile()).isEqualTo(session.resolve("abc.txt"));
        assertThat(session.resolve("abc.stdout.json")).exists();
        assertThat(session.resolve("abc.log.txt")).content().contains("→ Bash(step 1)", "Claude: done: hello");
        assertThat(dir.resolve("out").resolve("x").resolve("_running")).isEmptyDirectory();
        assertThat(Files.readString(res.outputFile())).isEqualTo("done: hello & goodbye");
    }

    @Test
    void messageDeliveredDuringTheRunReachesTheSameSession(@TempDir Path dir) throws Exception {
        ClaudeRunner runner = fakeRunner(dir, 6, 300);
        Task t = task(RunMode.NEW, null, "count");
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        var future = pool.submit(() -> runner.run(t, new Folder(dir.toString(), List.of(), null)));
        Path stream = runner.runningStdout(t);
        long until = System.currentTimeMillis() + 20_000;
        while (!(runner.isLive("abc") && Files.exists(stream) && Files.readString(stream).contains("step 1"))) {
            assertThat(System.currentTimeMillis()).isLessThan(until);
            Thread.sleep(50);
        }
        long offset = runner.deliver("abc", "also say hi");
        assertThat(offset).isPositive();

        var res = future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(res.text()).isEqualTo("done: count | with: also say hi");
        Path log = res.outputFile().resolveSibling("abc.log.txt");
        assertThat(log).content().contains("✉ Message from the caller (approved): also say hi", "Claude: got: also say hi");
        // after the run nothing can be delivered any more
        assertThat(runner.isLive("abc")).isFalse();
        assertThat(runner.deliver("abc", "too late")).isEqualTo(-1);
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

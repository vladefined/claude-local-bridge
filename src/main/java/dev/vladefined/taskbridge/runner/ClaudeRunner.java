package dev.vladefined.taskbridge.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.vladefined.taskbridge.config.BridgeProperties;
import dev.vladefined.taskbridge.config.BridgeProperties.Folder;
import dev.vladefined.taskbridge.core.Task;

/**
 * Runs {@code claude -p} for an approved task.
 *
 * <p>The task text goes in through stdin, never through the command line. On
 * Windows {@code claude} is often a {@code .cmd} shim, and arguments passed to a
 * batch file are re-parsed by cmd.exe, so text on the command line could break
 * out of its quoting. Only fixed flags and validated values are arguments.
 */
@Component
public class ClaudeRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeRunner.class);
    private static final int MAX_OUTPUT_BYTES = 2_000_000;

    private final BridgeProperties props;
    private final ObjectMapper json;

    public ClaudeRunner(BridgeProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public List<String> buildCommand(Task task, Folder folder) {
        List<String> cmd = new ArrayList<>(props.runner().command());
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("json");
        switch (task.mode()) {
            case NEW -> { }
            case CONTINUE -> cmd.add("--continue");
            case RESUME -> {
                cmd.add("--resume");
                cmd.add(task.resumeSessionId());
            }
        }
        if (!folder.allowedTools().isEmpty()) {
            cmd.add("--allowedTools");
            cmd.add(String.join(",", folder.allowedTools()));
        }
        return cmd;
    }

    public RunResult run(Task task, Folder folder) {
        Path dir = Path.of(folder.path());
        if (!Files.isDirectory(dir)) {
            return new RunResult(false, "Folder does not exist: " + dir, null);
        }
        Duration timeout = folder.timeout() != null ? folder.timeout() : props.runner().defaultTimeout();
        List<String> cmd = buildCommand(task, folder);
        log.info("task {}: running {} in {}", task.id(), cmd, dir);

        Process p;
        try {
            p = new ProcessBuilder(cmd).directory(dir.toFile()).start();
        } catch (IOException e) {
            return new RunResult(false, "Could not start Claude Code: " + e.getMessage(), null);
        }
        CompletableFuture<byte[]> out = CompletableFuture.supplyAsync(() -> readCapped(p.getInputStream()));
        CompletableFuture<byte[]> err = CompletableFuture.supplyAsync(() -> readCapped(p.getErrorStream()));
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(task.text().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            p.destroyForcibly();
            return new RunResult(false, "Could not pass the task to Claude Code: " + e.getMessage(), null);
        }

        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new RunResult(false, "Timed out after " + timeout.toMinutes() + " min", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new RunResult(false, "Interrupted", null);
        }
        String stdout = new String(out.join(), StandardCharsets.UTF_8);
        String stderr = new String(err.join(), StandardCharsets.UTF_8);
        return parse(p.exitValue(), stdout, stderr);
    }

    RunResult parse(int exitCode, String stdout, String stderr) {
        try {
            JsonNode node = json.readTree(stdout);
            String result = node.path("result").asText("");
            String session = node.path("session_id").asText(null);
            boolean isError = node.path("is_error").asBoolean(false);
            return new RunResult(exitCode == 0 && !isError, result, session);
        } catch (IOException e) {
            String text = !stdout.isBlank() ? stdout : stderr;
            return new RunResult(false, "exit " + exitCode + ": " + text.strip(), null);
        }
    }

    private static byte[] readCapped(InputStream in) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (buf.size() < MAX_OUTPUT_BYTES) {
                    buf.write(chunk, 0, Math.min(n, MAX_OUTPUT_BYTES - buf.size()));
                }
            }
        } catch (IOException ignored) {
            // process was killed
        }
        return buf.toByteArray();
    }
}

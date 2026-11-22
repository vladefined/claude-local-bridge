package dev.vladefined.taskbridge.runner;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
 *
 * <p>stdout and stderr go straight to files under {@code runner.output-dir}: nothing
 * is held in memory while the process runs, and a full pipe can never block it.
 * The full result text is saved as {@code <folder>/<session id>/<task id>.txt};
 * callers decide how much of it to show.
 */
@Component
public class ClaudeRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeRunner.class);
    /** Bigger stdout is not parsed as JSON (would need that much memory); the raw file is kept. */
    private static final long MAX_PARSE_BYTES = 64L * 1024 * 1024;

    public static final String RUNNING_DIR = "_running";
    static final String FAILED_DIR = "_failed";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");

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
        String summaryPrompt = props.runner().summaryPrompt();
        if (summaryPrompt != null && !summaryPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(summaryPrompt);
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
            return new RunResult(false, "Folder does not exist: " + dir, null, null);
        }
        // outputs/<folder>/_running/ while it runs; moved to outputs/<folder>/<session>/ afterwards
        Path projectOut = Path.of(props.runner().outputDir()).toAbsolutePath().resolve(task.folder());
        Path outDir = projectOut.resolve(RUNNING_DIR);
        Path stdout = outDir.resolve(task.id() + ".stdout.json");
        Path stderr = outDir.resolve(task.id() + ".stderr.txt");
        Path full = outDir.resolve(task.id() + ".txt");
        Duration timeout = folder.timeout() != null ? folder.timeout() : props.runner().defaultTimeout();
        List<String> cmd = buildCommand(task, folder);
        log.info("task {}: running {} in {}", task.id(), cmd, dir);

        Process p;
        try {
            Files.createDirectories(outDir);
            p = new ProcessBuilder(cmd).directory(dir.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
        } catch (IOException e) {
            return new RunResult(false, "Could not start Claude Code: " + e.getMessage(), null, null);
        }
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(task.text().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            p.destroyForcibly();
            return new RunResult(false, "Could not pass the task to Claude Code: " + e.getMessage(), null, null);
        }

        String failure = null;
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                failure = "Timed out after " + timeout.toMinutes() + " min";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            failure = "Interrupted";
        }
        RunResult parsed = failure != null
                ? new RunResult(false, failure + tail(stderr), null, null)
                : parse(p.exitValue(), stdout, stderr);
        try {
            Files.writeString(full, parsed.text(), StandardCharsets.UTF_8);
            Path finalDir = projectOut.resolve(sessionDir(parsed.sessionId()));
            Files.createDirectories(finalDir);
            for (Path f : List.of(stdout, stderr)) {
                if (Files.exists(f)) {
                    Files.move(f, finalDir.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path moved = Files.move(full, finalDir.resolve(full.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            return new RunResult(parsed.ok(), parsed.text(), parsed.sessionId(), moved);
        } catch (IOException e) {
            log.warn("task {}: could not save full output: {}", task.id(), e.toString());
            return parsed;
        }
    }

    /**
     * The session id comes from Claude's output, i.e. from outside: it only becomes a
     * directory name if it looks like an id, so it can never be "..\\..\\Windows".
     */
    public static String sessionDir(String sessionId) {
        return sessionId != null && SAFE_ID.matcher(sessionId).matches() ? sessionId : FAILED_DIR;
    }

    RunResult parse(int exitCode, Path stdout, Path stderr) {
        try {
            long size = Files.size(stdout);
            if (size > 0 && size <= MAX_PARSE_BYTES) {
                JsonNode node = json.readTree(stdout.toFile());
                if (node != null && node.has("result")) {
                    boolean isError = node.path("is_error").asBoolean(false);
                    return new RunResult(exitCode == 0 && !isError, node.path("result").asText(""),
                            node.path("session_id").asText(null), null);
                }
            }
            String raw = size > 0 && size <= MAX_PARSE_BYTES ? Files.readString(stdout) : "";
            return new RunResult(false, "exit " + exitCode
                    + (raw.isBlank() ? "" : ": " + raw.strip())
                    + (size > MAX_PARSE_BYTES ? " (stdout too large to parse, see " + stdout + ")" : "")
                    + tail(stderr), null, null);
        } catch (IOException e) {
            return new RunResult(false, "exit " + exitCode + ": unreadable output" + tail(stderr), null, null);
        }
    }

    /** Last part of stderr, for error messages. */
    private static String tail(Path stderr) {
        try {
            File f = stderr.toFile();
            if (!f.exists() || f.length() == 0) {
                return "";
            }
            String s = Files.readString(stderr, StandardCharsets.UTF_8);
            return "\nstderr: " + (s.length() > 2000 ? "…" + s.substring(s.length() - 2000) : s).strip();
        } catch (IOException e) {
            return "";
        }
    }
}

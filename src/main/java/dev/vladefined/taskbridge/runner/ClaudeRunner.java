package dev.vladefined.taskbridge.runner;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 *
 * <p>Claude runs with {@code --output-format stream-json --verbose}: every message and
 * tool call is one line in {@code <task id>.stdout.json}. While it runs, that file is
 * what {@link StreamLog#recentSteps} reads for progress; afterwards a readable
 * transcript is written next to it as {@code <task id>.log.txt}.
 *
 * <p>Input is {@code --input-format stream-json} too: the task text is the first user
 * message, and stdin stays open while the agent works, so messages the owner approved
 * during the run ({@link #deliver}) reach the same session. The agent picks them up
 * between tool calls. stdin is closed as soon as a {@code result} event appears that was
 * written after the last message sent: the agent has answered everything it was given.
 * A message sent after an answer was written keeps stdin open until the agent answers
 * it too. Once closed, further messages are refused, never silently dropped.
 */
@Component
public class ClaudeRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeRunner.class);
    /** Bigger stdout is not parsed (a single line could need that much memory); the raw file is kept. */
    private static final long MAX_PARSE_BYTES = 256L * 1024 * 1024;
    public static final String STDOUT_SUFFIX = ".stdout.json";
    public static final String LOG_SUFFIX = ".log.txt";

    public static final String RUNNING_DIR = "_running";
    static final String FAILED_DIR = "_failed";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");

    private final BridgeProperties props;
    private final ObjectMapper json;
    private final Map<String, LiveSession> live = new ConcurrentHashMap<>();

    public ClaudeRunner(BridgeProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public List<String> buildCommand(Task task, Folder folder) {
        List<String> cmd = new ArrayList<>(props.runner().command());
        cmd.add("-p");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");     // required by stream-json in -p mode
        cmd.add("--input-format");
        cmd.add("stream-json");   // the task and later messages are JSON lines on stdin
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
        Path stdout = runningStdout(task);
        Path stderr = outDir.resolve(task.id() + ".stderr.txt");
        Path full = outDir.resolve(task.id() + ".txt");
        Path transcript = outDir.resolve(task.id() + LOG_SUFFIX);
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
        LiveSession session = new LiveSession(p.getOutputStream(), stdout);
        if (session.send(task.text(), json) < 0) {
            p.destroyForcibly();
            return new RunResult(false, "Could not pass the task to Claude Code", null, null);
        }
        live.put(task.id(), session);

        String failure = null;
        try {
            failure = await(p, session, timeout);
        } finally {
            live.remove(task.id());
            session.close();
        }
        RunResult parsed = failure != null
                ? new RunResult(false, failure + tail(stderr), null, null)
                : parse(p.exitValue(), stdout, stderr);
        try {
            Files.writeString(full, parsed.text(), StandardCharsets.UTF_8);
            writeTranscript(task, stdout, transcript, session.delivered());
            Path finalDir = projectOut.resolve(sessionDir(parsed.sessionId()));
            Files.createDirectories(finalDir);
            for (Path f : List.of(stdout, stderr, transcript)) {
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

    /** Where the live stream of a running task is written. */
    public Path runningStdout(Task task) {
        return Path.of(props.runner().outputDir()).toAbsolutePath().resolve(task.folder())
                .resolve(RUNNING_DIR).resolve(task.id() + STDOUT_SUFFIX);
    }

    /** A file saved next to the finished task's result file ({@code <task id>.txt}), or null. */
    public static Path sibling(Task task, String suffix) {
        if (task.outputPath() == null) {
            return null;
        }
        return Path.of(task.outputPath()).resolveSibling(task.id() + suffix);
    }

    /**
     * Waits for the process, closing stdin once the agent has answered.
     *
     * @return a failure text, or null if the process ended by itself
     */
    private String await(Process p, LiveSession session, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        ResultWatcher watcher = new ResultWatcher(session.stdout);
        try {
            while (p.isAlive()) {
                if (System.nanoTime() > deadline) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                    return "Timed out after " + timeout.toMinutes() + " min";
                }
                long answeredAt = watcher.lastResultOffset(json);
                if (answeredAt >= 0) {
                    session.closeIfAnsweredAfterLastMessage(answeredAt);
                }
                Thread.sleep(250);
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return "Interrupted";
        }
    }

    /** Is the agent for this task running and still accepting messages? */
    public boolean isLive(String taskId) {
        LiveSession s = live.get(taskId);
        return s != null && !s.isClosed();
    }

    /**
     * Hands an approved message to the running agent.
     *
     * @return the stream offset at which it was delivered, or -1 if the agent no longer
     *         accepts messages (finished, answering, or not running)
     */
    public long deliver(String taskId, String text) {
        LiveSession s = live.get(taskId);
        return s == null ? -1 : s.send(text, json);
    }

    /** The task's stdin, one JSON user message per line. Closed exactly once; then send() refuses. */
    public static final class LiveSession {
        private final OutputStream stdin;
        final Path stdout;
        private final List<StreamLog.Insert> delivered = new ArrayList<>();
        private long lastSentAt;
        private boolean closed;

        public LiveSession(OutputStream stdin, Path stdout) {
            this.stdin = stdin;
            this.stdout = stdout;
        }

        /** @return the stream offset at sending time, or -1 if stdin is closed */
        public synchronized long send(String text, ObjectMapper json) {
            if (closed) {
                return -1;
            }
            try {
                long offset = Files.exists(stdout) ? Files.size(stdout) : 0;
                var msg = json.createObjectNode();
                msg.put("type", "user");
                var inner = msg.putObject("message");
                inner.put("role", "user");
                inner.put("content", text);
                stdin.write((json.writeValueAsString(msg) + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                lastSentAt = offset;
                if (!delivered.isEmpty() || offset > 0) {
                    delivered.add(new StreamLog.Insert(offset, text));
                } else {
                    delivered.add(null);      // the task itself, not an added message
                }
                return offset;
            } catch (IOException e) {
                closed = true;
                return -1;
            }
        }

        public synchronized boolean isClosed() {
            return closed;
        }

        /**
         * Close stdin if the answer at {@code resultOffset} was written after the last
         * message was sent. Checked and closed under the same lock as {@link #send}, so a
         * message is either before the close (and answered) or refused.
         */
        public synchronized boolean closeIfAnsweredAfterLastMessage(long resultOffset) {
            if (!closed && resultOffset >= lastSentAt) {
                close();
                return true;
            }
            return false;
        }

        public synchronized void close() {
            if (!closed) {
                closed = true;
                try {
                    stdin.close();
                } catch (IOException ignored) {
                    // the process is gone already
                }
            }
        }

        synchronized List<StreamLog.Insert> delivered() {
            return delivered.stream().filter(java.util.Objects::nonNull).toList();
        }
    }

    /** Reads the growing stdout file from where it stopped and reports {@code result} events. */
    public static final class ResultWatcher {
        private final Path file;
        private long pos;
        private final java.io.ByteArrayOutputStream partial = new java.io.ByteArrayOutputStream();

        private long lineStart;

        public ResultWatcher(Path file) {
            this.file = file;
        }

        /** @return the start offset of the last result line read so far, or -1 if none (yet) */
        private long lastResult = -1;

        public long lastResultOffset(ObjectMapper json) {
            readMore(json);
            return lastResult;
        }

        private boolean readMore(ObjectMapper json) {
            boolean seen = false;
            try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
                long len = f.length();
                if (len <= pos) {
                    return false;
                }
                f.seek(pos);
                byte[] buf = new byte[(int) Math.min(len - pos, 1 << 20)];
                f.readFully(buf);
                long at = pos;
                pos += buf.length;
                for (byte b : buf) {
                    at++;
                    if (b == '\n') {
                        String line = partial.toString(StandardCharsets.UTF_8);
                        partial.reset();
                        if (line.contains("\"result\"")) {
                            try {
                                if (StreamLog.isFinal(json.readTree(line))) {
                                    lastResult = lineStart;
                                    seen = true;
                                }
                            } catch (IOException ignored) {
                                // not JSON
                            }
                        }
                        lineStart = at;
                    } else if (partial.size() < (8 << 20)) {
                        partial.write(b);
                    }
                }
            } catch (IOException e) {
                return false;
            }
            return seen;
        }
    }

    private void writeTranscript(Task task, Path stdout, Path transcript, List<StreamLog.Insert> inserts) {
        try {
            if (Files.exists(stdout) && Files.size(stdout) <= MAX_PARSE_BYTES) {
                StreamLog.writeTranscript(stdout, transcript, json, inserts);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("task {}: could not write the transcript: {}", task.id(), e.toString());
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
                StreamLog.Final fin = StreamLog.findFinal(stdout, json);
                if (fin != null) {
                    return new RunResult(exitCode == 0 && !fin.isError(), fin.result(), fin.sessionId(), null);
                }
            }
            String raw = size > 0 && size <= MAX_PARSE_BYTES ? tailOf(stdout) : "";
            return new RunResult(false, "exit " + exitCode
                    + (raw.isBlank() ? "" : ": " + raw.strip())
                    + (size > MAX_PARSE_BYTES ? " (stdout too large to parse, see " + stdout + ")" : "")
                    + tail(stderr), null, null);
        } catch (IOException e) {
            return new RunResult(false, "exit " + exitCode + ": unreadable output" + tail(stderr), null, null);
        }
    }

    /** Last part of stdout, for error messages (a stream can be long). */
    private static String tailOf(Path stdout) throws IOException {
        String s = Files.readString(stdout, StandardCharsets.UTF_8);
        return s.length() > 2000 ? "…" + s.substring(s.length() - 2000) : s;
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

package dev.vladefined.taskbridge.runner;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads Claude Code's {@code --output-format stream-json} output: one JSON event per
 * line (system init, assistant messages with text and tool calls, tool results, and a
 * {@code result} event at the end of every turn).
 *
 * <p>Everything here is read line by line from the file on disk; the whole stream is
 * never held in memory. Text taken from events is shown as plain text only.
 *
 * <p>Positions in the stream are byte offsets. A <em>cursor</em> is the offset just
 * after the last complete line; messages added during the run are placed in progress
 * and in the log at the offset where they were handed to the agent.
 */
public final class StreamLog {

    /** Longest single step / tool input shown in progress. */
    static final int STEP_CHARS = 300;
    /** How much of each tool result goes into the readable log. */
    static final int TOOL_RESULT_CHARS = 1500;
    /** Progress reads only the end of a running stream. */
    static final int TAIL_BYTES = 512 * 1024;
    /** Counting steps after a cursor reads at most this much; more means "far behind". */
    static final int SINCE_MAX_BYTES = 4 * 1024 * 1024;

    private StreamLog() {
    }

    /** The final answer. With several turns (messages added after an answer) the answers are joined. */
    public record Final(String result, boolean isError, String sessionId) {
    }

    /** A message handed to the agent at {@code offset} in the stream. */
    public record Insert(long offset, String text) {
    }

    /** The last steps and the cursor they end at. */
    public record Snapshot(List<String> steps, long cursor) {
    }

    /**
     * All {@code result} events, joined. A single JSON object with a {@code result} field
     * (the old json format, or a stand-in for claude) is accepted too.
     */
    public static Final findFinal(Path stdout, ObjectMapper json) throws IOException {
        List<String> results = new ArrayList<>();
        boolean[] error = {false};
        String[] session = {null};
        forEachLine(stdout, 0, true, (offset, line) -> {
            JsonNode n = parse(line, json);
            if (isFinal(n)) {
                results.add(n.path("result").asText(""));
                error[0] = n.path("is_error").asBoolean(false);
                session[0] = n.path("session_id").asText(null);
            }
        });
        if (results.isEmpty()) {
            return null;
        }
        return new Final(String.join("\n\n", results), error[0], session[0]);
    }

    /** True for a {@code result} event (or the single-object json format). */
    static boolean isFinal(JsonNode n) {
        if (n == null || !n.has("result")) {
            return false;
        }
        String type = n.path("type").asText("");
        return type.isEmpty() || "result".equals(type);
    }

    /** Writes a readable transcript of the whole run, with added messages where they were delivered. */
    public static void writeTranscript(Path stdout, Path out, ObjectMapper json, List<Insert> inserts)
            throws IOException {
        Iterator<Insert> ins = sorted(inserts).iterator();
        Insert[] next = {ins.hasNext() ? ins.next() : null};
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            IOException[] failed = {null};
            forEachLine(stdout, 0, true, (offset, line) -> {
                try {
                    while (next[0] != null && next[0].offset() <= offset) {
                        w.write("\n" + insertLine(next[0], true) + "\n");
                        next[0] = ins.hasNext() ? ins.next() : null;
                    }
                    for (String s : describe(parse(line, json), true)) {
                        w.write(s);
                        w.write("\n");
                    }
                } catch (IOException e) {
                    failed[0] = e;
                }
            });
            while (next[0] != null) {
                w.write("\n" + insertLine(next[0], true) + "\n");
                next[0] = ins.hasNext() ? ins.next() : null;
            }
            if (failed[0] != null) {
                throw failed[0];
            }
        }
    }

    public static void writeTranscript(Path stdout, Path out, ObjectMapper json) throws IOException {
        writeTranscript(stdout, out, json, List.of());
    }

    /** The last {@code max} steps of a stream that may still be growing. */
    public static List<String> recentSteps(Path stdout, int max, ObjectMapper json) throws IOException {
        return snapshot(stdout, max, json, List.of()).steps();
    }

    /** The last {@code max} steps (added messages included) and the cursor after the last complete line. */
    public static Snapshot snapshot(Path stdout, int max, ObjectMapper json, List<Insert> inserts)
            throws IOException {
        if (!Files.isRegularFile(stdout)) {
            return new Snapshot(List.of(), 0);
        }
        Deque<String> steps = new ArrayDeque<>();
        long len = Files.size(stdout);
        long start = Math.max(0, len - TAIL_BYTES);
        Iterator<Insert> ins = sorted(inserts).stream().filter(i -> i.offset() >= start).iterator();
        Insert[] next = {ins.hasNext() ? ins.next() : null};
        long cursor = forEachLine(stdout, start, (offset, line) -> {
            while (next[0] != null && next[0].offset() <= offset) {
                add(steps, insertLine(next[0], false), max);
                next[0] = ins.hasNext() ? ins.next() : null;
            }
            for (String s : describe(parse(line, json), false)) {
                add(steps, s, max);
            }
        });
        while (next[0] != null && next[0].offset() <= cursor) {
            add(steps, insertLine(next[0], false), max);
            next[0] = ins.hasNext() ? ins.next() : null;
        }
        return new Snapshot(new ArrayList<>(steps), Math.max(cursor, start));
    }

    /**
     * How many steps the agent made after {@code cursor}; {@code Integer.MAX_VALUE} if the
     * cursor is not a position in this stream or is very far behind.
     */
    public static int stepsSince(Path stdout, long cursor, ObjectMapper json) throws IOException {
        if (!Files.isRegularFile(stdout)) {
            return cursor == 0 ? 0 : Integer.MAX_VALUE;
        }
        long len = Files.size(stdout);
        if (cursor < 0 || cursor > len || len - cursor > SINCE_MAX_BYTES) {
            return Integer.MAX_VALUE;
        }
        int[] n = {0};
        forEachLine(stdout, cursor, (offset, line) -> n[0] += describe(parse(line, json), false).size());
        return n[0];
    }

    private static void add(Deque<String> steps, String s, int max) {
        steps.addLast(s);
        if (steps.size() > max) {
            steps.removeFirst();
        }
    }

    private static List<Insert> sorted(List<Insert> inserts) {
        List<Insert> l = new ArrayList<>(inserts == null ? List.of() : inserts);
        l.sort(Comparator.comparingLong(Insert::offset));
        return l;
    }

    private static String insertLine(Insert i, boolean full) {
        String text = full ? i.text().strip() : cut(oneLine(i.text()), STEP_CHARS);
        return "✉ Message from the caller (approved): " + (full ? indent(text) : text);
    }

    /**
     * Calls {@code onLine(offset, line)} for every complete line starting at {@code from}
     * (or at the first line start after it, when {@code from} is inside a line).
     *
     * @return the offset just after the last complete line (the cursor)
     */
    static long forEachLine(Path file, long from, BiConsumer<Long, String> onLine) throws IOException {
        return forEachLine(file, from, false, onLine);
    }

    /** @param withTail also pass an unterminated last line (for a finished stream) */
    static long forEachLine(Path file, long from, boolean withTail, BiConsumer<Long, String> onLine)
            throws IOException {
        long pos = from;
        boolean skipFirst = false;
        if (from > 0) {
            try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
                f.seek(from - 1);
                skipFirst = f.read() != '\n';      // started mid-line: that partial line is dropped
            }
        }
        long lineStart = from;
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(from);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            for (int r; (r = in.read(chunk)) > 0; ) {
                for (int i = 0; i < r; i++) {
                    pos++;
                    if (chunk[i] == '\n') {
                        if (skipFirst) {
                            skipFirst = false;
                        } else {
                            onLine.accept(lineStart, buf.toString(StandardCharsets.UTF_8));
                        }
                        buf.reset();
                        lineStart = pos;
                    } else {
                        buf.write(chunk[i]);
                    }
                }
            }
            if (withTail && buf.size() > 0 && !skipFirst) {
                onLine.accept(lineStart, buf.toString(StandardCharsets.UTF_8));
            }
        }
        return lineStart;
    }

    private static JsonNode parse(String line, ObjectMapper json) {
        String t = line.strip();
        if (t.isEmpty() || t.charAt(0) != '{') {
            return null;
        }
        try {
            return json.readTree(t);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * One event as readable lines.
     *
     * @param full true for the transcript (tool results included), false for progress steps
     */
    static List<String> describe(JsonNode n, boolean full) {
        List<String> out = new ArrayList<>();
        if (n == null) {
            return out;
        }
        switch (n.path("type").asText("")) {
            case "system" -> {
                if ("init".equals(n.path("subtype").asText()) && full) {
                    out.add("[start] session " + n.path("session_id").asText("?")
                            + (n.hasNonNull("model") ? ", model " + n.path("model").asText() : ""));
                }
            }
            case "assistant" -> {
                for (JsonNode c : content(n)) {
                    switch (c.path("type").asText("")) {
                        case "text" -> {
                            String text = c.path("text").asText("").strip();
                            if (!text.isEmpty()) {
                                out.add(full ? "\nClaude: " + text + "\n" : "Claude: " + cut(oneLine(text), STEP_CHARS));
                            }
                        }
                        case "tool_use" -> out.add("→ " + toolCall(c));
                        default -> { }
                    }
                }
            }
            case "user" -> {
                if (!full) {
                    break;
                }
                for (JsonNode c : content(n)) {
                    if ("tool_result".equals(c.path("type").asText())) {
                        String text = toolResultText(c.path("content"));
                        String head = c.path("is_error").asBoolean(false) ? "  ← error: " : "  ← ";
                        out.add(head + indent(cut(text.strip(), TOOL_RESULT_CHARS)));
                    }
                }
            }
            case "result" -> {
                String end = "[end] " + (n.path("is_error").asBoolean(false) ? "error" : "done")
                        + (n.has("num_turns") ? ", turns " + n.path("num_turns").asInt() : "")
                        + (n.has("duration_ms") ? ", " + n.path("duration_ms").asLong() / 1000 + " s" : "")
                        + (n.has("total_cost_usd")
                                ? String.format(java.util.Locale.ROOT, ", $%.4f", n.path("total_cost_usd").asDouble())
                                : "");
                out.add(full ? "\n" + end : end);
            }
            default -> { }
        }
        return out;
    }

    private static Iterable<JsonNode> content(JsonNode n) {
        JsonNode c = n.path("message").path("content");
        return c.isArray() ? c : List.of();
    }

    /** "Read(src/App.java)", "Bash(mvn test)", otherwise the tool name and its compact input. */
    static String toolCall(JsonNode c) {
        String name = c.path("name").asText("?");
        JsonNode in = c.path("input");
        String arg = null;
        for (String key : List.of("command", "file_path", "path", "pattern", "url", "query", "description")) {
            if (in.hasNonNull(key)) {
                arg = in.path(key).asText();
                break;
            }
        }
        if (arg == null) {
            arg = in.isMissingNode() || in.isEmpty() ? "" : in.toString();
        }
        return name + "(" + cut(oneLine(arg), STEP_CHARS) + ")";
    }

    private static String toolResultText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            Iterator<JsonNode> it = content.elements();
            while (it.hasNext()) {
                JsonNode p = it.next();
                if ("text".equals(p.path("type").asText())) {
                    sb.append(p.path("text").asText()).append('\n');
                } else {
                    sb.append('[').append(p.path("type").asText("?")).append("]\n");
                }
            }
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    private static String indent(String s) {
        return s.replace("\n", "\n    ");
    }

    static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

package dev.vladefined.taskbridge.runner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads Claude Code's {@code --output-format stream-json} output: one JSON event per
 * line (system init, assistant messages with text and tool calls, tool results, and a
 * final {@code result} event).
 *
 * <p>Everything here is read line by line from the file on disk; the whole stream is
 * never held in memory. Text taken from events is shown as plain text only.
 */
public final class StreamLog {

    /** Longest single step / tool input shown in progress. */
    static final int STEP_CHARS = 300;
    /** How much of each tool result goes into the readable log. */
    static final int TOOL_RESULT_CHARS = 1500;
    /** Progress reads only the end of a running stream. */
    static final int TAIL_BYTES = 512 * 1024;

    private StreamLog() {
    }

    /** The final event: what used to be the single JSON object of {@code --output-format json}. */
    public record Final(String result, boolean isError, String sessionId) {
    }

    /**
     * The last {@code result} event. A single JSON object with a {@code result} field
     * (the old json format, or a stand-in for claude) is accepted too.
     */
    public static Final findFinal(Path stdout, ObjectMapper json) throws IOException {
        Final last = null;
        try (BufferedReader r = Files.newBufferedReader(stdout, StandardCharsets.UTF_8)) {
            for (String line; (line = r.readLine()) != null; ) {
                JsonNode n = parse(line, json);
                if (n == null || !n.has("result")) {
                    continue;
                }
                String type = n.path("type").asText("");
                if (type.isEmpty() || "result".equals(type)) {
                    last = new Final(n.path("result").asText(""), n.path("is_error").asBoolean(false),
                            n.path("session_id").asText(null));
                }
            }
        }
        return last;
    }

    /** Writes a readable transcript of the whole run. */
    public static void writeTranscript(Path stdout, Path out, ObjectMapper json) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(stdout, StandardCharsets.UTF_8);
             BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (String line; (line = r.readLine()) != null; ) {
                for (String s : describe(parse(line, json), true)) {
                    w.write(s);
                    w.write("\n");
                }
            }
        }
    }

    /** The last {@code max} steps of a stream that may still be growing. */
    public static List<String> recentSteps(Path stdout, int max, ObjectMapper json) throws IOException {
        if (!Files.isRegularFile(stdout)) {
            return List.of();
        }
        Deque<String> steps = new ArrayDeque<>();
        for (String line : tailLines(stdout)) {
            for (String s : describe(parse(line, json), false)) {
                steps.addLast(s);
                if (steps.size() > max) {
                    steps.removeFirst();
                }
            }
        }
        return new ArrayList<>(steps);
    }

    /** Complete lines from the end of the file; a line cut by the tail window or still being written is dropped. */
    static List<String> tailLines(Path file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            long start = Math.max(0, len - TAIL_BYTES);
            byte[] buf = new byte[(int) (len - start)];
            f.seek(start);
            f.readFully(buf);
            String s = new String(buf, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(List.of(s.split("\n", -1)));
            if (start > 0 && !lines.isEmpty()) {
                lines.remove(0);
            }
            if (!lines.isEmpty()) {
                lines.remove(lines.size() - 1);   // after the last \n: empty or incomplete
            }
            return lines;
        }
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
                        + (n.has("total_cost_usd") ? String.format(java.util.Locale.ROOT, ", $%.4f",n.path("total_cost_usd").asDouble()) : "");
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

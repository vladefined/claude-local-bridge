package dev.vladefined.taskbridge.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls the agent's short summary out of its answer. The runner asks Claude to end
 * with a line {@code SUMMARY: ...}; if that line is there, it is shown first in
 * Telegram and in the result toast, and removed from the body so it is not shown twice.
 * If the agent forgot it, there is simply no summary.
 */
public final class Summary {

    public static final int MAX_CHARS = 300;

    // "SUMMARY: ...", also tolerating markdown around it ("**Summary:** ...") and "Итог:"
    private static final Pattern LINE = Pattern.compile(
            "^[\\s*_#>-]*(?:SUMMARY|ИТОГ)[\\s*_]*[:：][\\s*_]*(.+?)[\\s*_]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public record Split(String body, String summary) {
    }

    private Summary() {
    }

    public static Split split(String text) {
        if (text == null || text.isBlank()) {
            return new Split(text == null ? "" : text, null);
        }
        String trimmed = text.stripTrailing();
        int start = trimmed.lastIndexOf('\n') + 1;
        Matcher m = LINE.matcher(trimmed.substring(start));
        if (!m.matches()) {
            return new Split(text, null);
        }
        String summary = m.group(1).strip();
        if (summary.length() > MAX_CHARS) {
            summary = summary.substring(0, MAX_CHARS) + "…";
        }
        return new Split(trimmed.substring(0, start).stripTrailing(), summary);
    }
}

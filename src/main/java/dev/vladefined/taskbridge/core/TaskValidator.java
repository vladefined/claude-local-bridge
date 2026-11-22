package dev.vladefined.taskbridge.core;

import java.util.regex.Pattern;

/**
 * "What you approve is what runs." The text shown to the human must be the exact
 * text that reaches Claude, so characters that render differently from what they
 * are (bidi overrides, zero-width and other invisible format characters, control
 * characters) are refused outright instead of being shown.
 */
public final class TaskValidator {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");

    private TaskValidator() {
    }

    public static void validate(NewTask t, int maxChars) {
        validateText(t.text(), maxChars);
        if (t.mode() == null) {
            throw new IllegalArgumentException("mode is required");
        }
        if (t.mode() == RunMode.RESUME) {
            // The session id ends up as a command-line argument: allow only an id shape,
            // so it can never be read as an option like --dangerously-skip-permissions.
            if (t.sessionId() == null || !SESSION_ID.matcher(t.sessionId()).matches()) {
                throw new IllegalArgumentException("mode RESUME needs a sessionId of [A-Za-z0-9-]");
            }
        } else if (t.sessionId() != null) {
            throw new IllegalArgumentException("sessionId is only allowed with mode RESUME");
        }
    }

    /** The same rules for a task and for a message added to a running task. */
    public static void validateText(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is empty");
        }
        if (text.length() > maxChars) {
            throw new IllegalArgumentException("text longer than " + maxChars + " characters");
        }
        int bad = firstHiddenChar(text);
        if (bad >= 0) {
            throw new IllegalArgumentException(String.format(
                    "text contains an invisible or control character U+%04X at index %d",
                    (int) text.charAt(bad), bad));
        }
    }

    /** Index of the first character that is not visible as itself, or -1. */
    static int firstHiddenChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t') {
                continue;
            }
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                return i;
            }
        }
        return -1;
    }

    /** Sender is self-declared and only shown as a label: keep it short and printable. */
    public static String cleanSender(String sender) {
        if (sender == null || sender.isBlank()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        sender.codePoints()
                .filter(cp -> !Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT)
                .limit(80)
                .forEach(sb::appendCodePoint);
        return sb.toString().strip();
    }
}

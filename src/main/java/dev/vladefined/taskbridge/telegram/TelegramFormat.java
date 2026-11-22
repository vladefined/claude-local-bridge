package dev.vladefined.taskbridge.telegram;

import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.UiText;

/**
 * Message texts. Everything is sent as plain text (no parse_mode), so nothing in
 * a task can turn into formatting, hidden links or fake buttons.
 */
public final class TelegramFormat {

    public static final int LIMIT = 4096;

    private TelegramFormat() {
    }

    public static String request(UiText ui, Task t) {
        return ui.t("task.title", t.id()) + "\n"
                + ui.t("task.folder") + ": " + t.folder() + "\n"
                + ui.t("task.mode") + ": " + mode(ui, t) + "\n"
                + ui.t("task.sender") + ": " + t.sender() + "\n"
                + "SHA-256: " + t.shortHash() + "\n"
                + "────────────\n"
                + t.text();
    }

    public static String decided(UiText ui, Task t) {
        return request(ui, t) + "\n────────────\n" + verdict(ui, t);
    }

    static String verdict(UiText ui, Task t) {
        String where = switch (t.decidedBy() == null ? "" : t.decidedBy()) {
            case "telegram" -> ".telegram";
            case "desktop" -> ".desktop";
            default -> "";
        };
        return switch (t.status()) {
            case EXPIRED -> ui.t("verdict.expired");
            case REJECTED -> ui.t("verdict.rejected" + where);
            default -> ui.t("verdict.approved" + where);
        };
    }

    /**
     * One message: the (already shortened) result. The text is cut once more only as a
     * last guard against Telegram's limit; with the default max-result-chars it never is.
     */
    public static String result(UiText ui, Task t) {
        String head = switch (t.status()) {
            case DONE -> ui.t("result.done", t.id());
            default -> ui.t("result.failed", t.id());
        };
        String body = t.result() == null || t.result().isBlank() ? ui.t("result.empty") : t.result();
        String note = t.resultTruncated()
                ? "\n…\n" + ui.t("result.truncated", t.result().length())
                : "";
        String headLine = head + (t.sessionId() != null ? "\nsession: " + t.sessionId() : "") + "\n\n"
                + (t.summary() != null ? ui.t("result.summary", t.summary()) + "\n\n" : "");
        int room = LIMIT - headLine.length() - note.length();
        if (body.length() > room) {
            body = body.substring(0, room);
        }
        return headLine + body + note;
    }

    private static String mode(UiText ui, Task t) {
        return switch (t.mode()) {
            case NEW -> ui.t("mode.new");
            case CONTINUE -> ui.t("mode.continue");
            case RESUME -> ui.t("mode.resume", t.resumeSessionId());
        };
    }
}

package dev.vladefined.taskbridge.telegram;

import dev.vladefined.taskbridge.access.AccessPolicy;
import dev.vladefined.taskbridge.access.FolderRequest;
import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.TaskMessage;
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
                + (t.extraToolList().isEmpty() ? ""
                        : ui.t("task.extra-tools", AccessPolicy.describe(t.extraToolList())) + "\n")
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

    /** Approval request for a message to a running task: the agent's latest steps, then the exact text. */
    public static String messageRequest(UiText ui, Task t, TaskMessage m, java.util.List<String> since,
                                        java.util.List<String> recent) {
        StringBuilder sb = new StringBuilder();
        sb.append(ui.t("msg.title", t.id(), t.folder())).append('\n')
                .append(ui.t("msg.sender")).append(": ").append(m.sender()).append('\n')
                .append("SHA-256: ").append(m.shortHash()).append('\n');
        if (since == null || since.isEmpty()) {
            sb.append(ui.t("msg.since.none")).append('\n')
                    .append(ui.t("msg.recent")).append('\n');
            steps(sb, ui, recent);
        } else {
            sb.append(ui.t("msg.since", since.size())).append('\n');
            steps(sb, ui, since);
        }
        sb.append("────────────\n").append(m.text());
        String s = sb.toString();
        return s.length() > LIMIT - 200 ? s.substring(0, LIMIT - 200) : s;
    }

    private static void steps(StringBuilder sb, UiText ui, java.util.List<String> steps) {
        if (steps == null || steps.isEmpty()) {
            sb.append(ui.t("msg.none")).append('\n');
            return;
        }
        for (String s : steps) {
            sb.append(s.length() > 200 ? s.substring(0, 200) + "…" : s).append('\n');
        }
    }

    /** The message request after something happened to it. */
    public static String messageDecided(UiText ui, Task t, TaskMessage m) {
        String head = ui.t("msg.title", t.id(), t.folder()) + "\nSHA-256: " + m.shortHash()
                + "\n────────────\n" + m.text() + "\n────────────\n";
        String where = switch (m.decidedBy() == null ? "" : m.decidedBy()) {
            case "telegram" -> ".telegram";
            case "desktop" -> ".desktop";
            default -> "";
        };
        String verdict = switch (m.status()) {
            case DELIVERED -> ui.t("msg.delivered");
            case UNDELIVERED -> ui.t("msg.undelivered");
            case REJECTED -> ui.t("verdict.rejected" + where);
            case EXPIRED -> ui.t("verdict.expired");
            default -> ui.t("verdict.approved" + where);
        };
        String s = head + verdict;
        return s.length() > LIMIT ? s.substring(s.length() - LIMIT) : s;
    }

    /** Approval request for a folder change: exactly what changes, the reason, and warnings. */
    public static String folderRequest(UiText ui, FolderRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append(ui.t("folder.title." + r.kind().name().toLowerCase(java.util.Locale.ROOT), r.name())).append('\n')
                .append(ui.t("folder.path")).append(": ").append(r.path()).append('\n');
        if (!r.addTools().isEmpty()) {
            sb.append(ui.t("folder.add-tools")).append(": ").append(AccessPolicy.describe(r.addTools())).append('\n');
        }
        if (!r.removeTools().isEmpty()) {
            sb.append(ui.t("folder.remove-tools")).append(": ").append(String.join(", ", r.removeTools())).append('\n');
        }
        if (r.timeoutMinutes() != null) {
            sb.append(ui.t("folder.timeout", r.timeoutMinutes())).append('\n');
        }
        sb.append(ui.t("task.sender")).append(": ").append(r.sender()).append('\n');
        for (String w : r.warnings()) {
            sb.append(ui.t("folder.warning", w)).append('\n');
        }
        sb.append("────────────\n").append(ui.t("folder.reason")).append(": ").append(r.reason());
        String s = sb.toString();
        return s.length() > LIMIT - 200 ? s.substring(0, LIMIT - 200) : s;
    }

    public static String folderDecided(UiText ui, FolderRequest r) {
        String where = switch (r.decidedBy() == null ? "" : r.decidedBy()) {
            case "telegram" -> ".telegram";
            case "desktop" -> ".desktop";
            default -> "";
        };
        String verdict = switch (r.status()) {
            case APPLIED -> ui.t("folder.applied");
            case FAILED -> ui.t("folder.failed", r.error() == null ? "?" : r.error());
            case EXPIRED -> ui.t("verdict.expired");
            case REJECTED -> ui.t("verdict.rejected" + where);
            default -> "";
        };
        return folderRequest(ui, r) + "\n────────────\n" + verdict;
    }

    private static String mode(UiText ui, Task t) {
        return switch (t.mode()) {
            case NEW -> ui.t("mode.new");
            case CONTINUE -> ui.t("mode.continue");
            case RESUME -> ui.t("mode.resume", t.resumeSessionId());
        };
    }
}

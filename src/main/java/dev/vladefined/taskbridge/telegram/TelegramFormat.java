package dev.vladefined.taskbridge.telegram;

import dev.vladefined.taskbridge.core.Task;

/**
 * Message texts. Everything is sent as plain text (no parse_mode), so nothing in
 * a task can turn into formatting, hidden links or fake buttons.
 */
public final class TelegramFormat {

    public static final int LIMIT = 4096;

    private TelegramFormat() {
    }

    public static String request(Task t) {
        return "Задача " + t.id() + "\n"
                + "Папка: " + t.folder() + "\n"
                + "Режим: " + mode(t) + "\n"
                + "Отправитель (со слов запроса): " + t.sender() + "\n"
                + "SHA-256: " + t.shortHash() + "\n"
                + "────────────\n"
                + t.text();
    }

    public static String decided(Task t) {
        return request(t) + "\n────────────\n" + verdict(t);
    }

    static String verdict(Task t) {
        String where = switch (t.decidedBy() == null ? "" : t.decidedBy()) {
            case "telegram" -> " в Telegram";
            case "desktop" -> " с ПК";
            default -> "";
        };
        return switch (t.status()) {
            case EXPIRED -> "⌛ Истекло без решения";
            case REJECTED -> "❌ Отклонено" + where;
            default -> "✅ Подтверждено" + where;
        };
    }

    /**
     * One message: the (already shortened) result. The text is cut once more only as a
     * last guard against Telegram's limit; with the default max-result-chars it never is.
     */
    public static String result(Task t) {
        String head = switch (t.status()) {
            case DONE -> "Готово: " + t.id();
            default -> "Ошибка: " + t.id();
        };
        String body = t.result() == null || t.result().isBlank() ? "(пустой ответ)" : t.result();
        String note = t.resultTruncated()
                ? "\n…\n(обрезано, первые " + t.result().length() + " символов; полный вывод во вложении)"
                : "";
        String headLine = head + (t.sessionId() != null ? "\nsession: " + t.sessionId() : "") + "\n\n"
                + (t.summary() != null ? "Итог: " + t.summary() + "\n\n" : "");
        int room = LIMIT - headLine.length() - note.length();
        if (body.length() > room) {
            body = body.substring(0, room);
        }
        return headLine + body + note;
    }

    private static String mode(Task t) {
        return switch (t.mode()) {
            case NEW -> "новая сессия";
            case CONTINUE -> "продолжить последнюю сессию";
            case RESUME -> "сессия " + t.resumeSessionId();
        };
    }
}

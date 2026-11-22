package dev.vladefined.taskbridge.telegram;

import java.util.ArrayList;
import java.util.List;

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

    public static List<String> result(Task t) {
        String head = switch (t.status()) {
            case DONE -> "Готово: " + t.id();
            default -> "Ошибка: " + t.id();
        };
        String body = t.result() == null || t.result().isBlank() ? "(пустой ответ)" : t.result();
        List<String> parts = new ArrayList<>();
        String first = head + (t.sessionId() != null ? "\nsession: " + t.sessionId() : "") + "\n\n";
        String all = first + body;
        for (int i = 0; i < all.length(); i += LIMIT - 96) {
            parts.add(all.substring(i, Math.min(all.length(), i + LIMIT - 96)));
        }
        return parts;
    }

    private static String mode(Task t) {
        return switch (t.mode()) {
            case NEW -> "новая сессия";
            case CONTINUE -> "продолжить последнюю сессию";
            case RESUME -> "сессия " + t.resumeSessionId();
        };
    }
}

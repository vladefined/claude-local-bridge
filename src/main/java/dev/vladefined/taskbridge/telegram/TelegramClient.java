package dev.vladefined.taskbridge.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal Telegram Bot API client: the four calls the bridge needs, nothing else. */
public class TelegramClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String base;

    public TelegramClient(String token, ObjectMapper json) {
        this.json = json;
        this.base = "https://api.telegram.org/bot" + token + "/";
    }

    public record Button(String text, String data) {
    }

    public long sendMessage(long chatId, String text, List<Button> buttons, Long replyTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("link_preview_options", Map.of("is_disabled", true));
        if (replyTo != null) {
            body.put("reply_parameters", Map.of("message_id", replyTo, "allow_sending_without_reply", true));
        }
        if (buttons != null && !buttons.isEmpty()) {
            body.put("reply_markup", Map.of("inline_keyboard", List.of(buttons.stream()
                    .map(b -> Map.of("text", b.text(), "callback_data", b.data())).toList())));
        }
        return call("sendMessage", body, Duration.ofSeconds(20)).path("message_id").asLong();
    }

    /** Editing without reply_markup also removes the inline buttons. */
    public void editMessageText(long chatId, long messageId, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        body.put("link_preview_options", Map.of("is_disabled", true));
        call("editMessageText", body, Duration.ofSeconds(20));
    }

    public void answerCallbackQuery(String id, String text) {
        call("answerCallbackQuery", Map.of("callback_query_id", id, "text", text), Duration.ofSeconds(20));
    }

    public JsonNode getUpdates(long offset, int timeoutSec) {
        return call("getUpdates", Map.of("offset", offset, "timeout", timeoutSec,
                "allowed_updates", List.of("callback_query")), Duration.ofSeconds(timeoutSec + 15));
    }

    private JsonNode call(String method, Map<String, Object> body, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + method))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            JsonNode node = json.readTree(resp.body());
            if (!node.path("ok").asBoolean(false)) {
                // Never include the URL: it contains the bot token.
                throw new TelegramException(method + ": " + node.path("description").asText("HTTP " + resp.statusCode()));
            }
            return node.path("result");
        } catch (IOException e) {
            throw new TelegramException(method + ": " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramException(method + ": interrupted");
        }
    }

    public static class TelegramException extends RuntimeException {
        public TelegramException(String msg) {
            super(msg);
        }
    }
}

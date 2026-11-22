package dev.vladefined.taskbridge.telegram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.vladefined.taskbridge.config.BridgeProperties;
import dev.vladefined.taskbridge.core.ApprovalChannel;
import dev.vladefined.taskbridge.core.DecisionAttempt;
import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.access.FolderAccessService;
import dev.vladefined.taskbridge.access.FolderRequest;
import dev.vladefined.taskbridge.access.FolderRequestRepository;
import dev.vladefined.taskbridge.core.MessageDecisionAttempt;
import dev.vladefined.taskbridge.core.TaskMessage;
import dev.vladefined.taskbridge.core.TaskMessageRepository;
import dev.vladefined.taskbridge.core.TaskRepository;
import dev.vladefined.taskbridge.core.UiText;
import dev.vladefined.taskbridge.runner.ClaudeRunner;
import dev.vladefined.taskbridge.telegram.TelegramClient.Button;

/**
 * Approval in Telegram. Long polling, so no inbound port is needed for Telegram.
 * Button presses are accepted only from the configured chat; everything else is ignored.
 */
@Component
@ConditionalOnProperty(name = "bridge.telegram.enabled", havingValue = "true")
public class TelegramChannel implements ApprovalChannel, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);
    public static final String CHANNEL = "telegram";
    private static final long MAX_DOCUMENT_BYTES = 49L * 1024 * 1024;

    private final TelegramClient client;
    private final long chatId;
    private final TaskRepository repo;
    private final TaskMessageRepository messages;
    private final FolderRequestRepository folderRequests;
    private final ApplicationEventPublisher events;
    private final boolean sendLog;
    private final UiText ui;
    private volatile boolean running;
    private Thread poller;

    public TelegramChannel(BridgeProperties props, ObjectMapper json, TaskRepository repo,
                           TaskMessageRepository messages, FolderRequestRepository folderRequests,
                           ApplicationEventPublisher events, UiText ui) {
        this.messages = messages;
        this.folderRequests = folderRequests;
        this.ui = ui;
        if (props.telegram().botToken() == null || props.telegram().botToken().isBlank()
                || props.telegram().chatId() == 0) {
            throw new IllegalStateException("Telegram is enabled but BRIDGE_TELEGRAM_TOKEN / "
                    + "BRIDGE_TELEGRAM_CHAT_ID are not set");
        }
        this.client = new TelegramClient(props.telegram().botToken(), json);
        this.chatId = props.telegram().chatId();
        this.repo = repo;
        this.events = events;
        this.sendLog = props.telegram().sendLog();
    }

    @Override
    public void requestApproval(Task t) {
        long msgId = client.sendMessage(chatId, TelegramFormat.request(ui, t), java.util.List.of(
                new Button(ui.t("button.run"), "a:" + t.id()),
                new Button(ui.t("button.reject"), "r:" + t.id())), null);
        repo.setTelegramMessageId(t.id(), msgId);
    }

    @Override
    public void onDecided(Task t) {
        Long msgId = repo.find(t.id()).map(Task::telegramMessageId).orElse(null);
        if (msgId != null) {
            client.editMessageText(chatId, msgId, TelegramFormat.decided(ui, t));
        }
    }

    @Override
    public void onFinished(Task t) {
        client.sendMessage(chatId, TelegramFormat.result(ui, t), null, t.telegramMessageId());
        if (t.resultTruncated() && t.outputPath() != null) {
            sendFile(t, Path.of(t.outputPath()), ui.t("file.output"));
        }
        if (sendLog) {
            Path runLog = ClaudeRunner.sibling(t, ClaudeRunner.LOG_SUFFIX);
            if (runLog != null && Files.isRegularFile(runLog)) {
                sendFile(t, runLog, ui.t("file.log"));
            }
        }
    }

    @Override
    public void requestMessageApproval(Task t, TaskMessage m, java.util.List<String> since,
                                       java.util.List<String> recent) {
        long msgId = client.sendMessage(chatId, TelegramFormat.messageRequest(ui, t, m, since, recent), java.util.List.of(
                new Button(ui.t("msg.button.send"), "ma:" + m.id()),
                new Button(ui.t("msg.button.reject"), "mr:" + m.id())), t.telegramMessageId());
        messages.setTelegramMessageId(m.id(), msgId);
    }

    @Override
    public void onMessageUpdated(Task t, TaskMessage m) {
        if (m.status() == dev.vladefined.taskbridge.core.MessageStatus.APPROVED) {
            return;                    // DELIVERED or UNDELIVERED follows at once
        }
        Long msgId = messages.find(m.id()).map(TaskMessage::telegramMessageId).orElse(null);
        if (msgId != null) {
            client.editMessageText(chatId, msgId, TelegramFormat.messageDecided(ui, t, m));
        }
    }

    @Override
    public void requestFolderApproval(FolderRequest r) {
        long msgId = client.sendMessage(chatId, TelegramFormat.folderRequest(ui, r), java.util.List.of(
                new Button(ui.t("folder.button.allow"), "fa:" + r.id()),
                new Button(ui.t("folder.button.reject"), "fr:" + r.id())), null);
        folderRequests.setTelegramMessageId(r.id(), msgId);
    }

    @Override
    public void onFolderRequestUpdated(FolderRequest r) {
        Long msgId = folderRequests.find(r.id()).map(FolderRequest::telegramMessageId).orElse(null);
        if (msgId != null) {
            client.editMessageText(chatId, msgId, TelegramFormat.folderDecided(ui, r));
        }
    }

    private void sendFile(Task t, Path file, String what) {
        try {
            if (Files.size(file) == 0) {
                return;
            }
            if (Files.size(file) <= MAX_DOCUMENT_BYTES) {
                client.sendDocument(chatId, file, what + ": " + t.id(), t.telegramMessageId());
            } else {
                client.sendMessage(chatId, ui.t("file.too-big", what, file), null,
                        t.telegramMessageId());
            }
        } catch (IOException e) {
            log.warn("task {}: cannot read {}: {}", t.id(), file, e.toString());
        }
    }

    void handleUpdate(JsonNode update) {
        JsonNode cb = update.path("callback_query");
        if (cb.isMissingNode()) {
            return;
        }
        String cbId = cb.path("id").asText();
        long from = cb.path("from").path("id").asLong();
        long chat = cb.path("message").path("chat").path("id").asLong();
        String data = cb.path("data").asText("");
        if (chat != chatId || from != chatId) {
            log.warn("ignored button press from user {} in chat {}", from, chat);
            return;
        }
        int colon = data.indexOf(':');
        if (colon < 1 || colon == data.length() - 1) {
            return;
        }
        String kind = data.substring(0, colon);
        if (kind.equals("fa") || kind.equals("fr")) {
            String rid = data.substring(colon + 1);
            boolean allow = kind.equals("fa");
            events.publishEvent(new FolderAccessService.DecisionAttempt(rid, allow, CHANNEL));
            FolderRequest r = folderRequests.find(rid).orElse(null);
            String answer = r == null ? ui.t("callback.not-found")
                    : r.decidedBy() != null && !CHANNEL.equals(r.decidedBy()) ? ui.t("callback.already", r.status())
                    : allow ? ui.t("callback.allowed") : ui.t("callback.rejected");
            client.answerCallbackQuery(cbId, answer);
            return;
        }
        if (kind.equals("ma") || kind.equals("mr")) {
            String mid = data.substring(colon + 1);
            boolean send = kind.equals("ma");
            events.publishEvent(new MessageDecisionAttempt(mid, send, CHANNEL));
            TaskMessage m = messages.find(mid).orElse(null);
            String answer = m == null ? ui.t("callback.not-found")
                    : m.decidedBy() != null && !CHANNEL.equals(m.decidedBy()) ? ui.t("callback.already", m.status())
                    : send ? ui.t("callback.sending") : ui.t("callback.rejected");
            client.answerCallbackQuery(cbId, answer);
            return;
        }
        if (colon != 1) {
            return;
        }
        boolean approve = data.charAt(0) == 'a';
        events.publishEvent(new DecisionAttempt(data.substring(2), approve, CHANNEL));
        Task t = repo.find(data.substring(2)).orElse(null);
        String answer = t == null ? ui.t("callback.not-found")
                : t.decidedBy() != null && !CHANNEL.equals(t.decidedBy()) ? ui.t("callback.already", t.status())
                : approve ? ui.t("callback.running") : ui.t("callback.rejected");
        client.answerCallbackQuery(cbId, answer);
    }

    private void pollLoop() {
        long offset = 0;
        while (running) {
            try {
                for (JsonNode u : client.getUpdates(offset, 25)) {
                    offset = Math.max(offset, u.path("update_id").asLong() + 1);
                    try {
                        handleUpdate(u);
                    } catch (RuntimeException e) {
                        log.warn("update failed: {}", e.toString());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("polling error: {}", e.getMessage());
                sleep(Duration.ofSeconds(5));
            }
        }
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void start() {
        running = true;
        poller = Thread.ofVirtual().name("telegram-poller").start(this::pollLoop);
    }

    @Override
    public void stop() {
        running = false;
        if (poller != null) {
            poller.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

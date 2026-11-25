package dev.vladefined.taskbridge.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the bridge is allowed to do is declared here. Anything not listed
 * (a folder, a tool) is refused, so the remote side can only pick from a menu.
 */
@ConfigurationProperties("bridge")
public record BridgeProperties(
        String hmacSecret,
        @DefaultValue("5m") Duration maxClockSkew,
        @DefaultValue("30m") Duration approvalTtl,
        @DefaultValue("3000") int maxTaskChars,
        @DefaultValue("3500") int maxResultChars,
        Telegram telegram,
        Desktop desktop,
        Runner runner,
        Map<String, Folder> folders) {

    public BridgeProperties {
        if (hmacSecret == null || hmacSecret.length() < 32) {
            throw new IllegalStateException(
                    "BRIDGE_HMAC_SECRET must be set and at least 32 characters long");
        }
        if (folders == null || folders.isEmpty()) {
            throw new IllegalStateException("At least one entry under bridge.folders is required");
        }
        for (String name : folders.keySet()) {
            // folder names become directory names under runner.output-dir
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
                throw new IllegalStateException("bridge.folders key must be [A-Za-z0-9._-]: " + name);
            }
        }
        folders = Map.copyOf(folders);
    }

    public record Telegram(
            @DefaultValue("false") boolean enabled,
            String botToken,
            long chatId,
            /** Attach the readable run log (every message and tool call) to the result. */
            @DefaultValue("true") boolean sendLog) {
    }

    public record Desktop(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("8766") int port,
            @DefaultValue("300") int toastPreviewChars,
            /** Toast buttons decide in the background via a claude-bridge: URL handler, no browser. */
            @DefaultValue("true") boolean protocolButtons) {
    }

    public record Runner(
            @DefaultValue("claude") List<String> command,
            /** Total limit for one task when its folder sets none. */
            @DefaultValue("2h") Duration defaultTimeout,
            @DefaultValue("outputs") String outputDir,
            /** Full output files older than this are deleted; 0 keeps them forever. */
            @DefaultValue("30d") Duration outputRetention,
            /**
             * Appended to Claude's system prompt (--append-system-prompt) so that it ends
             * its answer with a "SUMMARY: ..." line. Empty turns it off. It is a
             * command-line argument, so characters cmd.exe treats specially are refused.
             */
            @DefaultValue(Runner.DEFAULT_SUMMARY_PROMPT) String summaryPrompt,
            /**
             * The task is stopped when its stream gets no new line for this long. A command
             * running in the foreground sends a heartbeat every 30 s, so a long silence
             * means the agent is stuck.
             */
            @DefaultValue("5m") Duration idleTimeout,
            /**
             * The same limit while Claude Code reports background commands: those can run
             * silently for up to 2 hours before Claude Code stops them itself.
             */
            @DefaultValue("2h") Duration backgroundIdleTimeout,
            /**
             * After the agent has answered, stdin is closed and nothing runs in the
             * background, the process is stopped if it has not exited within this time.
             */
            @DefaultValue("30s") Duration exitGrace) {

        public static final String DEFAULT_SUMMARY_PROMPT =
                "When you finish, end your final answer with one separate last line that starts with "
                + "SUMMARY: and says in one or two short sentences what you did and the outcome, "
                + "in the language of the task.";

        public Runner(List<String> command, Duration defaultTimeout, String outputDir,
                      Duration outputRetention, String summaryPrompt) {
            this(command, defaultTimeout, outputDir, outputRetention, summaryPrompt,
                    Duration.ofMinutes(5), Duration.ofHours(2), Duration.ofSeconds(30));
        }

        @ConstructorBinding
        public Runner {
            if (summaryPrompt != null && summaryPrompt.matches("(?s).*[\"%^&|<>!\r\n].*")) {
                throw new IllegalStateException(
                        "bridge.runner.summary-prompt must not contain \" % ^ & | < > ! or line breaks");
            }
        }
    }

    public record Folder(
            String path,
            @DefaultValue List<String> allowedTools,
            Duration timeout) {
    }
}

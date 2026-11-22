package dev.vladefined.taskbridge.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
        folders = Map.copyOf(folders);
    }

    public record Telegram(
            @DefaultValue("false") boolean enabled,
            String botToken,
            long chatId) {
    }

    public record Desktop(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("8766") int port,
            @DefaultValue("300") int toastPreviewChars) {
    }

    public record Runner(
            @DefaultValue("claude") List<String> command,
            @DefaultValue("20m") Duration defaultTimeout,
            @DefaultValue("outputs") String outputDir) {
    }

    public record Folder(
            String path,
            @DefaultValue List<String> allowedTools,
            Duration timeout) {
    }
}

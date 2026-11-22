package dev.vladefined.taskbridge.core;

import java.time.Instant;

public record Task(
        String id,
        String folder,
        RunMode mode,
        String resumeSessionId,
        String text,
        String sender,
        String sha256,
        TaskStatus status,
        String decidedBy,
        Instant createdAt,
        Instant decidedAt,
        Long telegramMessageId,
        String desktopToken,
        String result,
        String sessionId,
        Instant finishedAt,
        boolean resultTruncated,
        String outputPath,
        /** The agent's own one-line summary of what it did, if it wrote one. */
        String summary,
        /** Tools allowed for this task only, approved together with it (comma-separated), or null. */
        String extraTools) {

    public java.util.List<String> extraToolList() {
        return extraTools == null || extraTools.isBlank() ? java.util.List.of()
                : java.util.List.of(extraTools.split(","));
    }

    /** First 12 hex chars of the text hash: enough to compare by eye across channels. */
    public String shortHash() {
        return sha256.substring(0, 12);
    }
}

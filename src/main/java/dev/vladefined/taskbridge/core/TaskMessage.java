package dev.vladefined.taskbridge.core;

import java.time.Instant;

/**
 * A message the caller wants to add to a task that is already running. It goes to the
 * agent only after the owner approved its exact text, like a task.
 *
 * @param stepsBehind     how many agent steps happened after the progress snapshot the
 *                        caller based the message on (shown to the owner)
 * @param deliveredOffset where in the task's event stream the message was handed to the
 *                        agent (used to place it in progress and in the log)
 */
public record TaskMessage(
        String id,
        String taskId,
        String text,
        String sender,
        String sha256,
        MessageStatus status,
        String decidedBy,
        Instant createdAt,
        Instant decidedAt,
        Long telegramMessageId,
        String desktopToken,
        int stepsBehind,
        Long deliveredOffset) {

    public String shortHash() {
        return sha256.substring(0, 12);
    }
}

package dev.vladefined.taskbridge.core;

/** A button press on a message (not a task) approval; {@link TaskService} decides. */
public record MessageDecisionAttempt(String messageId, boolean approve, String channel) {
}

package dev.vladefined.taskbridge.core;

/**
 * Published by an approval channel when a human pressed a button.
 * Channels never change task state themselves: {@link TaskService} decides,
 * and only the first attempt for a task wins.
 */
public record DecisionAttempt(String taskId, boolean approve, String channel) {
}

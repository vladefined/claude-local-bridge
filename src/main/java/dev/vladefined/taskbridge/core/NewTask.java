package dev.vladefined.taskbridge.core;

/** What the remote side may choose. Everything else comes from local configuration. */
public record NewTask(String folder, String text, RunMode mode, String sessionId, String sender) {
}

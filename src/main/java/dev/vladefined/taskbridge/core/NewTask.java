package dev.vladefined.taskbridge.core;

/** What the remote side may choose. Everything else comes from local configuration. */
public record NewTask(String folder, String text, RunMode mode, String sessionId, String sender,
                      java.util.List<String> extraTools) {

    public NewTask(String folder, String text, RunMode mode, String sessionId, String sender) {
        this(folder, text, mode, sessionId, sender, null);
    }
}

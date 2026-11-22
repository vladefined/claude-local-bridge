package dev.vladefined.taskbridge.core;

/** How the local Claude Code session is chosen. */
public enum RunMode {
    /** Fresh session. */
    NEW,
    /** {@code claude -p --continue}: the most recent session in the folder. */
    CONTINUE,
    /** {@code claude -p --resume <sessionId>}: a specific session. */
    RESUME
}

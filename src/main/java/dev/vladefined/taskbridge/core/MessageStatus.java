package dev.vladefined.taskbridge.core;

/**
 * PENDING -> APPROVED -> DELIVERED | UNDELIVERED, or PENDING -> REJECTED | EXPIRED.
 * UNDELIVERED: approved (or still pending) when the task had already finished; nothing
 * reached the agent.
 */
public enum MessageStatus {
    PENDING, APPROVED, REJECTED, EXPIRED, DELIVERED, UNDELIVERED;

    public boolean isDecision() {
        return this == APPROVED || this == REJECTED || this == EXPIRED;
    }
}

package dev.vladefined.taskbridge.core;

public enum TaskStatus {
    PENDING, APPROVED, REJECTED, EXPIRED, RUNNING, DONE, FAILED;

    public boolean isDecision() {
        return this == APPROVED || this == REJECTED || this == EXPIRED;
    }
}

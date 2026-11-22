package dev.vladefined.taskbridge.access;

import java.time.Instant;
import java.util.List;

/**
 * A request from the remote side to change which folders tasks may use, or which tools
 * they get there. Nothing changes until the owner approves it.
 *
 * @param path      ADD: the real path (resolved before the owner sees it)
 * @param addTools  tools to allow (ADD: the folder's list)
 * @param warnings  shown to the owner with the request, one per line
 */
public record FolderRequest(
        String id,
        Kind kind,
        String name,
        String path,
        List<String> addTools,
        List<String> removeTools,
        Integer timeoutMinutes,
        String reason,
        String sender,
        List<String> warnings,
        Status status,
        String decidedBy,
        Instant createdAt,
        Instant decidedAt,
        Long telegramMessageId,
        String desktopToken,
        String error) {

    public enum Kind { ADD, CHANGE, REMOVE }

    /** APPLIED: approved and in effect. FAILED: approved, but could not be applied (see error). */
    public enum Status {
        PENDING, APPLIED, REJECTED, EXPIRED, FAILED;

        public boolean isDecision() {
            return this != PENDING;
        }
    }
}

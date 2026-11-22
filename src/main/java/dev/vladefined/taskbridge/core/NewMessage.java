package dev.vladefined.taskbridge.core;

/**
 * A message for a running task.
 *
 * @param cursor the {@code cursor} from {@code GET /tasks/{id}/progress} the caller read
 *               before writing this; required, so a message is always based on what the
 *               agent is actually doing
 */
public record NewMessage(String text, Long cursor, String sender) {
}

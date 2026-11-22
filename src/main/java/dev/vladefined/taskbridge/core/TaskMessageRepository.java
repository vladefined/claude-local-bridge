package dev.vladefined.taskbridge.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Messages to running tasks, kept for audit like tasks. */
@Repository
public class TaskMessageRepository {

    private final JdbcTemplate jdbc;

    public TaskMessageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_messages (
                    id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    text TEXT NOT NULL,
                    sender TEXT,
                    sha256 TEXT NOT NULL,
                    status TEXT NOT NULL,
                    decided_by TEXT,
                    created_at INTEGER NOT NULL,
                    decided_at INTEGER,
                    telegram_message_id INTEGER,
                    desktop_token TEXT,
                    steps_behind INTEGER NOT NULL DEFAULT 0,
                    delivered_offset INTEGER
                )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS task_messages_task ON task_messages(task_id)");
    }

    public void insert(TaskMessage m) {
        jdbc.update("""
                INSERT INTO task_messages (id, task_id, text, sender, sha256, status, created_at,
                                           desktop_token, steps_behind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                m.id(), m.taskId(), m.text(), m.sender(), m.sha256(), m.status().name(),
                m.createdAt().toEpochMilli(), m.desktopToken(), m.stepsBehind());
    }

    public Optional<TaskMessage> find(String id) {
        return jdbc.query("SELECT * FROM task_messages WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<TaskMessage> findByDesktopToken(String token) {
        return jdbc.query("SELECT * FROM task_messages WHERE desktop_token = ?", MAPPER, token)
                .stream().findFirst();
    }

    public List<TaskMessage> findByTask(String taskId) {
        return jdbc.query("SELECT * FROM task_messages WHERE task_id = ? ORDER BY created_at", MAPPER, taskId);
    }

    /** Same compare-and-set as for tasks: only the first decision wins. */
    public boolean tryDecide(String id, MessageStatus to, String decidedBy, Instant at) {
        if (!to.isDecision()) {
            throw new IllegalArgumentException("not a decision: " + to);
        }
        return jdbc.update("""
                UPDATE task_messages SET status = ?, decided_by = ?, decided_at = ?
                WHERE id = ? AND status = 'PENDING'""",
                to.name(), decidedBy, at.toEpochMilli(), id) == 1;
    }

    public void markDelivered(String id, long offset) {
        jdbc.update("UPDATE task_messages SET status = 'DELIVERED', delivered_offset = ? WHERE id = ?",
                offset, id);
    }

    /** Approved or still pending, but the task is gone: nothing reached the agent. */
    public boolean markUndelivered(String id, String reason, Instant at) {
        return jdbc.update("""
                UPDATE task_messages SET status = 'UNDELIVERED',
                       decided_by = COALESCE(decided_by, ?), decided_at = COALESCE(decided_at, ?)
                WHERE id = ? AND status IN ('PENDING', 'APPROVED')""",
                reason, at.toEpochMilli(), id) == 1;
    }

    public void setTelegramMessageId(String id, long messageId) {
        jdbc.update("UPDATE task_messages SET telegram_message_id = ? WHERE id = ?", messageId, id);
    }

    public List<TaskMessage> findPendingCreatedBefore(Instant cutoff) {
        return jdbc.query("SELECT * FROM task_messages WHERE status = 'PENDING' AND created_at < ?",
                MAPPER, cutoff.toEpochMilli());
    }

    /** Messages that can still change: pending or approved but not yet handed over. */
    public List<TaskMessage> findOpen() {
        return jdbc.query("SELECT * FROM task_messages WHERE status IN ('PENDING', 'APPROVED')", MAPPER);
    }

    private static final RowMapper<TaskMessage> MAPPER = TaskMessageRepository::map;

    private static TaskMessage map(ResultSet rs, int row) throws SQLException {
        long tg = rs.getLong("telegram_message_id");
        Long telegram = rs.wasNull() ? null : tg;
        long off = rs.getLong("delivered_offset");
        Long offset = rs.wasNull() ? null : off;
        long dec = rs.getLong("decided_at");
        Instant decided = rs.wasNull() ? null : Instant.ofEpochMilli(dec);
        return new TaskMessage(
                rs.getString("id"),
                rs.getString("task_id"),
                rs.getString("text"),
                rs.getString("sender"),
                rs.getString("sha256"),
                MessageStatus.valueOf(rs.getString("status")),
                rs.getString("decided_by"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                decided,
                telegram,
                rs.getString("desktop_token"),
                rs.getInt("steps_behind"),
                offset);
    }
}

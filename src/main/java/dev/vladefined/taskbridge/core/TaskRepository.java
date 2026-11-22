package dev.vladefined.taskbridge.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQLite-backed task log. Every task and every decision stays here for audit. */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    folder TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    resume_session_id TEXT,
                    text TEXT NOT NULL,
                    sender TEXT,
                    sha256 TEXT NOT NULL,
                    status TEXT NOT NULL,
                    decided_by TEXT,
                    created_at INTEGER NOT NULL,
                    decided_at INTEGER,
                    telegram_message_id INTEGER,
                    desktop_token TEXT,
                    result TEXT,
                    session_id TEXT,
                    finished_at INTEGER,
                    result_truncated INTEGER NOT NULL DEFAULT 0,
                    output_path TEXT,
                    summary TEXT
                )""");
        // databases created before the summary column existed
        boolean hasSummary = jdbc.queryForList("PRAGMA table_info(tasks)").stream()
                .anyMatch(c -> "summary".equals(c.get("name")));
        if (!hasSummary) {
            jdbc.execute("ALTER TABLE tasks ADD COLUMN summary TEXT");
        }
    }

    public void insert(Task t) {
        jdbc.update("""
                INSERT INTO tasks (id, folder, mode, resume_session_id, text, sender, sha256,
                                   status, created_at, desktop_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                t.id(), t.folder(), t.mode().name(), t.resumeSessionId(), t.text(), t.sender(),
                t.sha256(), t.status().name(), t.createdAt().toEpochMilli(), t.desktopToken());
    }

    public Optional<Task> find(String id) {
        return jdbc.query("SELECT * FROM tasks WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Task> findByDesktopToken(String token) {
        return jdbc.query("SELECT * FROM tasks WHERE desktop_token = ?", MAPPER, token)
                .stream().findFirst();
    }

    /**
     * Atomic compare-and-set from PENDING. This single UPDATE is what makes
     * "first channel wins" safe: two buttons pressed at the same moment cannot
     * both succeed.
     */
    public boolean tryDecide(String id, TaskStatus to, String decidedBy, Instant at) {
        if (!to.isDecision()) {
            throw new IllegalArgumentException("not a decision: " + to);
        }
        return jdbc.update("""
                UPDATE tasks SET status = ?, decided_by = ?, decided_at = ?
                WHERE id = ? AND status = 'PENDING'""",
                to.name(), decidedBy, at.toEpochMilli(), id) == 1;
    }

    public void setTelegramMessageId(String id, long messageId) {
        jdbc.update("UPDATE tasks SET telegram_message_id = ? WHERE id = ?", messageId, id);
    }

    public boolean markRunning(String id) {
        return jdbc.update("UPDATE tasks SET status = 'RUNNING' WHERE id = ? AND status = 'APPROVED'",
                id) == 1;
    }

    public void finish(String id, TaskStatus status, String result, boolean truncated, String outputPath,
                       String summary, String sessionId, Instant at) {
        jdbc.update("""
                UPDATE tasks SET status = ?, result = ?, result_truncated = ?, output_path = ?,
                                 summary = ?, session_id = ?, finished_at = ?
                WHERE id = ?""", status.name(), result, truncated ? 1 : 0, outputPath, summary, sessionId,
                at.toEpochMilli(), id);
    }

    public List<Task> findPendingCreatedBefore(Instant cutoff) {
        return jdbc.query("SELECT * FROM tasks WHERE status = 'PENDING' AND created_at < ?",
                MAPPER, cutoff.toEpochMilli());
    }

    /** Tasks that were approved or running when the process stopped. */
    public List<Task> findInterrupted() {
        return jdbc.query("SELECT * FROM tasks WHERE status IN ('APPROVED', 'RUNNING')", MAPPER);
    }

    private static final RowMapper<Task> MAPPER = TaskRepository::map;

    private static Task map(ResultSet rs, int row) throws SQLException {
        return new Task(
                rs.getString("id"),
                rs.getString("folder"),
                RunMode.valueOf(rs.getString("mode")),
                rs.getString("resume_session_id"),
                rs.getString("text"),
                rs.getString("sender"),
                rs.getString("sha256"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getString("decided_by"),
                instant(rs, "created_at"),
                instant(rs, "decided_at"),
                nullableLong(rs, "telegram_message_id"),
                rs.getString("desktop_token"),
                rs.getString("result"),
                rs.getString("session_id"),
                instant(rs, "finished_at"),
                rs.getInt("result_truncated") != 0,
                rs.getString("output_path"),
                rs.getString("summary"));
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : Instant.ofEpochMilli(v);
    }

}

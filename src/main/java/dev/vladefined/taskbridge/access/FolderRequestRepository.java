package dev.vladefined.taskbridge.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Folder requests and their outcome, kept for audit. */
@Repository
public class FolderRequestRepository {

    private final JdbcTemplate jdbc;

    public FolderRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS folder_requests (
                    id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    name TEXT NOT NULL,
                    path TEXT,
                    add_tools TEXT,
                    remove_tools TEXT,
                    timeout_minutes INTEGER,
                    reason TEXT,
                    sender TEXT,
                    warnings TEXT,
                    status TEXT NOT NULL,
                    decided_by TEXT,
                    created_at INTEGER NOT NULL,
                    decided_at INTEGER,
                    telegram_message_id INTEGER,
                    desktop_token TEXT,
                    error TEXT
                )""");
    }

    public void insert(FolderRequest r) {
        jdbc.update("""
                INSERT INTO folder_requests (id, kind, name, path, add_tools, remove_tools, timeout_minutes,
                                             reason, sender, warnings, status, created_at, desktop_token)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                r.id(), r.kind().name(), r.name(), r.path(), join(r.addTools()), join(r.removeTools()),
                r.timeoutMinutes(), r.reason(), r.sender(), String.join("\n", r.warnings()),
                r.status().name(), r.createdAt().toEpochMilli(), r.desktopToken());
    }

    public Optional<FolderRequest> find(String id) {
        return jdbc.query("SELECT * FROM folder_requests WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<FolderRequest> findByDesktopToken(String token) {
        return jdbc.query("SELECT * FROM folder_requests WHERE desktop_token = ?", MAPPER, token)
                .stream().findFirst();
    }

    /** Claim the decision: only the first press wins. The outcome is written with {@link #finish}. */
    public boolean tryClaim(String id, String decidedBy, Instant at) {
        return jdbc.update("""
                UPDATE folder_requests SET status = 'DECIDING', decided_by = ?, decided_at = ?
                WHERE id = ? AND status = 'PENDING'""", decidedBy, at.toEpochMilli(), id) == 1;
    }

    public void finish(String id, FolderRequest.Status status, String error) {
        jdbc.update("UPDATE folder_requests SET status = ?, error = ? WHERE id = ?", status.name(), error, id);
    }

    public void setTelegramMessageId(String id, long messageId) {
        jdbc.update("UPDATE folder_requests SET telegram_message_id = ? WHERE id = ?", messageId, id);
    }

    public List<FolderRequest> findPendingCreatedBefore(Instant cutoff) {
        return jdbc.query("SELECT * FROM folder_requests WHERE status = 'PENDING' AND created_at < ?",
                MAPPER, cutoff.toEpochMilli());
    }

    /** A crash between claim and finish leaves DECIDING: nothing was applied for sure, so FAILED. */
    public void failStuck() {
        jdbc.update("UPDATE folder_requests SET status = 'FAILED', error = 'bridge restarted while deciding' "
                + "WHERE status = 'DECIDING'");
    }

    private static String join(List<String> l) {
        return l == null || l.isEmpty() ? null : String.join(",", l);
    }

    private static List<String> split(String s, String sep) {
        return s == null || s.isBlank() ? List.of() : List.of(s.split(sep));
    }

    private static final RowMapper<FolderRequest> MAPPER = FolderRequestRepository::map;

    private static FolderRequest map(ResultSet rs, int row) throws SQLException {
        int tm = rs.getInt("timeout_minutes");
        Integer timeout = rs.wasNull() ? null : tm;
        long dec = rs.getLong("decided_at");
        Instant decided = rs.wasNull() ? null : Instant.ofEpochMilli(dec);
        long tg = rs.getLong("telegram_message_id");
        Long telegram = rs.wasNull() ? null : tg;
        String st = rs.getString("status");
        return new FolderRequest(
                rs.getString("id"),
                FolderRequest.Kind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                rs.getString("path"),
                split(rs.getString("add_tools"), ","),
                split(rs.getString("remove_tools"), ","),
                timeout,
                rs.getString("reason"),
                rs.getString("sender"),
                split(rs.getString("warnings"), "\n"),
                "DECIDING".equals(st) ? FolderRequest.Status.PENDING : FolderRequest.Status.valueOf(st),
                rs.getString("decided_by"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                decided,
                telegram,
                rs.getString("desktop_token"),
                rs.getString("error"));
    }
}

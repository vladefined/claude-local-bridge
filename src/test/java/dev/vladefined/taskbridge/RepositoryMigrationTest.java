package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import dev.vladefined.taskbridge.core.TaskRepository;

class RepositoryMigrationTest {

    @Test
    void oldDatabaseGetsTheSummaryColumn(@TempDir Path dir) {
        var ds = new SingleConnectionDataSource("jdbc:sqlite:" + dir.resolve("old.db"), true);
        try {
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY, folder TEXT NOT NULL, mode TEXT NOT NULL,"
                    + " resume_session_id TEXT, text TEXT NOT NULL, sender TEXT, sha256 TEXT NOT NULL,"
                    + " status TEXT NOT NULL, decided_by TEXT, created_at INTEGER NOT NULL, decided_at INTEGER,"
                    + " telegram_message_id INTEGER, desktop_token TEXT, result TEXT, session_id TEXT,"
                    + " finished_at INTEGER, result_truncated INTEGER NOT NULL DEFAULT 0, output_path TEXT)");
            jdbc.update("INSERT INTO tasks (id, folder, mode, text, sha256, status, created_at)"
                    + " VALUES ('old1', 'pibench', 'NEW', 'x', ?, 'DONE', 1)", "a".repeat(64));
            var repo = new TaskRepository(jdbc);
            new TaskRepository(jdbc);   // second start: column already there, no error
            assertThat(repo.find("old1")).get().extracting(t -> t.summary()).isNull();
        } finally {
            ds.destroy();
        }
    }
}

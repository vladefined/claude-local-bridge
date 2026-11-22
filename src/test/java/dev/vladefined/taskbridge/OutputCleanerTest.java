package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.vladefined.taskbridge.config.BridgeProperties;
import dev.vladefined.taskbridge.config.BridgeProperties.Folder;
import dev.vladefined.taskbridge.runner.OutputCleaner;

class OutputCleanerTest {

    static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");

    static OutputCleaner cleaner(Path root, Duration retention) {
        var props = new BridgeProperties("0123456789abcdef0123456789abcdef", Duration.ofMinutes(5),
                Duration.ofMinutes(30), 3000, 3500, null, null,
                new BridgeProperties.Runner(List.of("claude"), Duration.ofMinutes(20), root.toString(), retention),
                Map.of("pibench", new Folder("/tmp", List.of(), null)));
        return new OutputCleaner(props, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static Path file(Path p, Duration age) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.from(NOW.minus(age)));
        return p;
    }

    @Test
    void deletesOnlyOldFilesAndEmptySessionDirs(@TempDir Path root) throws Exception {
        Path old = file(root.resolve("pibench/sess-old/a.txt"), Duration.ofDays(40));
        Path fresh = file(root.resolve("pibench/sess-new/b.txt"), Duration.ofDays(1));
        Path running = file(root.resolve("pibench/_running/c.stdout.json"), Duration.ofDays(400));

        assertThat(cleaner(root, Duration.ofDays(30)).clean()).isEqualTo(1);
        assertThat(old).doesNotExist();
        assertThat(old.getParent()).doesNotExist();      // emptied session dir removed
        assertThat(fresh).exists();
        assertThat(running).exists();                     // never touch a running task
    }

    @Test
    void zeroRetentionKeepsEverything(@TempDir Path root) throws Exception {
        Path old = file(root.resolve("pibench/s/a.txt"), Duration.ofDays(4000));
        assertThat(cleaner(root, Duration.ZERO).clean()).isZero();
        assertThat(old).exists();
    }
}

package dev.vladefined.taskbridge.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.vladefined.taskbridge.config.BridgeProperties;

/**
 * Deletes full-output files older than {@code runner.output-retention}.
 * Retention is by age, not by count: a burst of new tasks must not wipe the output
 * of a session you are still working with. Files of running tasks are never touched.
 */
@Component
public class OutputCleaner {

    private static final Logger log = LoggerFactory.getLogger(OutputCleaner.class);

    private final Path root;
    private final Duration retention;
    private final Clock clock;

    public OutputCleaner(BridgeProperties props, Clock clock) {
        this.root = Path.of(props.runner().outputDir()).toAbsolutePath();
        this.retention = props.runner().outputRetention();
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT6H")
    public void scheduled() {
        clean();
    }

    /** @return number of files deleted */
    public int clean() {
        if (retention.isZero() || retention.isNegative() || !Files.isDirectory(root)) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(retention);
        int deleted = 0;
        List<Path> paths;
        try (Stream<Path> s = Files.walk(root)) {
            // deepest first, so directories come after their files
            paths = s.sorted(Comparator.reverseOrder()).toList();
        } catch (IOException e) {
            log.warn("output cleanup: cannot list {}: {}", root, e.toString());
            return 0;
        }
        for (Path p : paths) {
            if (p.equals(root) || isRunning(p)) {
                continue;
            }
            try {
                if (Files.isRegularFile(p)) {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                        Files.delete(p);
                        deleted++;
                    }
                } else if (Files.isDirectory(p) && isEmpty(p)) {
                    Files.delete(p);
                }
            } catch (IOException e) {
                log.warn("output cleanup: {}: {}", p, e.toString());
            }
        }
        if (deleted > 0) {
            log.info("output cleanup: deleted {} files older than {}", deleted, retention);
        }
        return deleted;
    }

    private boolean isRunning(Path p) {
        Path rel = root.relativize(p);
        return rel.getNameCount() >= 2 && rel.getName(1).toString().equals(ClaudeRunner.RUNNING_DIR);
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }
}

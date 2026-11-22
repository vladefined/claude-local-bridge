package dev.vladefined.taskbridge.runner;

import java.nio.file.Path;

/** @param text the full result text; @param outputFile where that full text was saved (may be null) */
public record RunResult(boolean ok, String text, String sessionId, Path outputFile) {
}

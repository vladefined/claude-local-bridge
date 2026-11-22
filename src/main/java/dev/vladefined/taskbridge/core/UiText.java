package dev.vladefined.taskbridge.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texts shown to the owner, in the language chosen by {@code bridge.ui.language}
 * ({@code en} by default, {@code ru} available). The texts live in
 * {@code ui/<language>.properties}; {0}, {1}... are replaced by the arguments.
 */
public final class UiText {

    private static final Pattern ARG = Pattern.compile("\\{(\\d)}");

    private final String language;
    private final Properties texts;

    private UiText(String language, Properties texts) {
        this.language = language;
        this.texts = texts;
    }

    public static UiText of(String language) {
        String lang = language == null || language.isBlank() ? "en" : language.strip().toLowerCase();
        if (!lang.matches("[a-z]{2,8}")) {
            throw new IllegalStateException("bridge.ui.language must be a language code like en or ru");
        }
        Properties p = new Properties();
        try (InputStream in = UiText.class.getResourceAsStream("/ui/" + lang + ".properties")) {
            if (in == null) {
                throw new IllegalStateException("No texts for bridge.ui.language=" + lang
                        + " (expected ui/" + lang + ".properties)");
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read ui/" + lang + ".properties", e);
        }
        return new UiText(lang, p);
    }

    public String language() {
        return language;
    }

    public Properties raw() {
        return texts;
    }

    /**
     * The text for {@code key} with {0}, {1}... replaced in one pass, so a "{1}" inside an
     * inserted value (task text, a path) is never replaced again. No quoting rules.
     */
    public String t(String key, Object... args) {
        String s = texts.getProperty(key);
        if (s == null) {
            throw new IllegalArgumentException("missing UI text: " + key + " (" + language + ")");
        }
        Matcher m = ARG.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            String v = i < args.length ? String.valueOf(args[i]) : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(v));
        }
        m.appendTail(out);
        return out.toString();
    }
}

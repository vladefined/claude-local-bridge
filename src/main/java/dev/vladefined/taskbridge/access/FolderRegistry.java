package dev.vladefined.taskbridge.access;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.vladefined.taskbridge.config.BridgeProperties;
import dev.vladefined.taskbridge.config.BridgeProperties.Folder;

/**
 * The folders tasks may run in: {@code bridge.folders} from the configuration, plus
 * changes the owner approved through folder requests. Approved changes are kept in
 * {@code bridge.folders-file} (default {@code %LOCALAPPDATA%\claude-local-bridge\folders.json})
 * and apply at once, without a restart.
 *
 * <p>File format: {@code {"folders": {name: {path, allowedTools, timeoutMinutes}}, "removed": [name]}}.
 * Entries there override configured folders of the same name; "removed" hides configured ones.
 */
@Component
public class FolderRegistry {

    private static final Logger log = LoggerFactory.getLogger(FolderRegistry.class);

    /** One folder as listed to the caller. {@code source}: "config" or "request". */
    public record Entry(String name, Folder folder, String source) {
    }

    private final Map<String, Folder> base;
    private final Path file;
    private final ObjectMapper json;
    private final Map<String, Folder> added = new TreeMap<>();
    private final TreeSet<String> removed = new TreeSet<>();

    public FolderRegistry(BridgeProperties props, ObjectMapper json,
                          @Value("${bridge.folders-file:}") String file) {
        this.base = props.folders();
        this.json = json;
        this.file = file == null || file.isBlank() ? defaultFile() : Path.of(file).toAbsolutePath();
        load();
    }

    public static Path defaultFile() {
        String local = System.getenv("LOCALAPPDATA");
        Path dir = local != null && !local.isBlank()
                ? Path.of(local, "claude-local-bridge")
                : Path.of(System.getProperty("user.home"), ".claude-local-bridge");
        return dir.resolve("folders.json");
    }

    public Path file() {
        return file;
    }

    public synchronized Optional<Folder> get(String name) {
        if (added.containsKey(name)) {
            return Optional.of(added.get(name));
        }
        if (removed.contains(name)) {
            return Optional.empty();
        }
        return Optional.ofNullable(base.get(name));
    }

    public boolean exists(String name) {
        return get(name).isPresent();
    }

    public synchronized List<Entry> list() {
        Map<String, Entry> out = new TreeMap<>();
        base.forEach((n, f) -> {
            if (!removed.contains(n)) {
                out.put(n, new Entry(n, f, "config"));
            }
        });
        added.forEach((n, f) -> out.put(n, new Entry(n, f, "request")));
        return new ArrayList<>(out.values());
    }

    /** Add or replace a folder; written to the file before it takes effect. */
    public synchronized void put(String name, Folder folder) throws IOException {
        Map<String, Folder> a = new TreeMap<>(added);
        TreeSet<String> r = new TreeSet<>(removed);
        a.put(name, folder);
        r.remove(name);
        save(a, r);
        added.clear();
        added.putAll(a);
        removed.clear();
        removed.addAll(r);
        log.info("folder {} set: {} tools {}", name, folder.path(), folder.allowedTools());
    }

    public synchronized void remove(String name) throws IOException {
        Map<String, Folder> a = new TreeMap<>(added);
        TreeSet<String> r = new TreeSet<>(removed);
        a.remove(name);
        if (base.containsKey(name)) {
            r.add(name);
        }
        save(a, r);
        added.clear();
        added.putAll(a);
        removed.clear();
        removed.addAll(r);
        log.info("folder {} removed", name);
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = json.readTree(file.toFile());
            root.path("folders").fields().forEachRemaining(e -> {
                JsonNode f = e.getValue();
                List<String> tools = new ArrayList<>();
                f.path("allowedTools").forEach(t -> tools.add(t.asText()));
                long minutes = f.path("timeoutMinutes").asLong(0);
                added.put(e.getKey(), new Folder(f.path("path").asText(), List.copyOf(tools),
                        minutes > 0 ? Duration.ofMinutes(minutes) : null));
            });
            root.path("removed").forEach(n -> removed.add(n.asText()));
            log.info("folders from {}: {} added/changed, {} removed", file, added.keySet(), removed);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private void save(Map<String, Folder> a, TreeSet<String> r) throws IOException {
        ObjectNode root = json.createObjectNode();
        ObjectNode folders = root.putObject("folders");
        for (var e : a.entrySet()) {
            ObjectNode f = folders.putObject(e.getKey());
            f.put("path", e.getValue().path());
            var tools = f.putArray("allowedTools");
            e.getValue().allowedTools().forEach(tools::add);
            if (e.getValue().timeout() != null) {
                f.put("timeoutMinutes", e.getValue().timeout().toMinutes());
            }
        }
        var rem = root.putArray("removed");
        r.forEach(rem::add);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** For the API: name -> (path, tools, timeout, source). */
    public static Map<String, Object> view(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.name());
        m.put("path", e.folder().path());
        m.put("allowedTools", e.folder().allowedTools());
        m.put("timeoutMinutes", e.folder().timeout() == null ? null : e.folder().timeout().toMinutes());
        m.put("source", e.source());
        return m;
    }
}

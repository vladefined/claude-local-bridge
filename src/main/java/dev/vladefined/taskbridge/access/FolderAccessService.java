package dev.vladefined.taskbridge.access;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.vladefined.taskbridge.config.BridgeProperties.Folder;
import dev.vladefined.taskbridge.core.ApprovalChannel;
import dev.vladefined.taskbridge.core.TaskValidator;

/**
 * Folder requests: the remote side asks, the owner approves the exact change, then it
 * is applied to the {@link FolderRegistry}. Every rule in {@link AccessPolicy} is
 * checked when the request arrives and again when it is applied.
 */
@Service
public class FolderAccessService {

    private static final Logger log = LoggerFactory.getLogger(FolderAccessService.class);
    public static final Duration TTL = Duration.ofMinutes(30);
    static final int MAX_PENDING = 5;
    static final int MAX_REASON_CHARS = 500;

    /** What the remote side sends. kind: add | change | remove. */
    public record NewFolderRequest(String kind, String name, String path, List<String> addTools,
                                   List<String> removeTools, Integer timeoutMinutes, String reason,
                                   String sender) {
    }

    /** A button press on a folder request. */
    public record DecisionAttempt(String requestId, boolean approve, String channel) {
    }

    private final FolderRequestRepository repo;
    private final FolderRegistry registry;
    private final List<ApprovalChannel> channels;
    private final Clock clock;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final Path home;
    private final List<Path> roots;

    /**
     * @param userHome the profile folder the path rules protect; empty = the real one
     *                 ({@code bridge.access.user-home} exists for tests only)
     */
    public FolderAccessService(FolderRequestRepository repo, FolderRegistry registry,
                               List<ApprovalChannel> channels, Clock clock, ObjectMapper json,
                               @org.springframework.beans.factory.annotation.Value("${bridge.access.user-home:}")
                               String userHome,
                               @org.springframework.beans.factory.annotation.Value("${bridge.access.roots:}")
                               List<String> roots) {
        this.roots = roots == null ? List.of() : roots.stream().filter(s -> s != null && !s.isBlank())
                .map(s -> Path.of(s.strip()).toAbsolutePath().normalize()).toList();
        if (this.roots.isEmpty()) {
            log.warn("bridge.access.roots is empty: requests for new folders will be refused");
        } else {
            log.info("folder requests limited to {}", this.roots);
        }
        this.home = Path.of(userHome == null || userHome.isBlank() ? System.getProperty("user.home") : userHome);
        this.repo = repo;
        this.registry = registry;
        this.channels = channels;
        this.clock = clock;
        this.json = json;
        repo.failStuck();
    }

    public Optional<FolderRequest> find(String id) {
        return repo.find(id);
    }

    /** The bridge's own directories: never grantable. */
    List<Path> protectedDirs() {
        List<Path> out = new ArrayList<>();
        out.add(Path.of(System.getProperty("user.dir")));          // DB, outputs, logs
        out.add(registry.file().getParent());                      // folders.json, bridge.env
        String cp = System.getProperty("java.class.path", "");
        if (cp.toLowerCase(Locale.ROOT).endsWith(".jar") && !cp.contains(java.io.File.pathSeparator)) {
            Path jarDir = Path.of(cp).toAbsolutePath().getParent();
            if (jarDir != null) {
                out.add(jarDir);
            }
        }
        return out;
    }

    Path home() {
        return home;
    }

    /** Where new folders may be requested (empty: nowhere). */
    public List<Path> roots() {
        return roots;
    }

    private Path checkedPath(String raw) {
        Path real = AccessPolicy.checkPath(raw, home(), protectedDirs());
        AccessPolicy.checkInsideRoots(real, roots);
        return real;
    }

    public FolderRequest submit(NewFolderRequest r) {
        FolderRequest.Kind kind = kind(r.kind());
        String name = AccessPolicy.checkFolderName(r.name());
        TaskValidator.validateText(r.reason(), MAX_REASON_CHARS);
        long pending = repo.findPendingCreatedBefore(clock.instant().plusSeconds(1)).size();
        if (pending >= MAX_PENDING) {
            throw new IllegalArgumentException("too many folder requests waiting for the owner (" + pending + ")");
        }
        List<String> add = List.of();
        List<String> remove = List.of();
        Integer timeout = null;
        String path;
        List<String> warnings = new ArrayList<>();
        Optional<Folder> current = registry.get(name);
        switch (kind) {
            case ADD -> {
                if (current.isPresent()) {
                    throw new IllegalArgumentException("folder " + name + " exists; use kind change");
                }
                path = checkedPath(r.path()).toString();
                add = AccessPolicy.checkTools(r.addTools());
                if (add.isEmpty()) {
                    throw new IllegalArgumentException("addTools: give at least one tool for the new folder");
                }
                if (r.removeTools() != null && !r.removeTools().isEmpty()) {
                    throw new IllegalArgumentException("removeTools makes no sense for a new folder");
                }
                timeout = AccessPolicy.checkTimeout(r.timeoutMinutes());
                for (FolderRegistry.Entry e : registry.list()) {
                    if (Path.of(e.folder().path()).toAbsolutePath().normalize().toString()
                            .equalsIgnoreCase(path)) {
                        warnings.add("the same path is already folder " + e.name());
                    }
                }
            }
            case CHANGE -> {
                Folder f = current.orElseThrow(() -> new IllegalArgumentException("unknown folder: " + name));
                path = f.path();
                add = AccessPolicy.checkTools(r.addTools()).stream()
                        .filter(t -> !f.allowedTools().contains(t)).toList();
                remove = r.removeTools() == null ? List.of() : r.removeTools().stream().map(String::strip).distinct().toList();
                for (String t : remove) {
                    if (!f.allowedTools().contains(t)) {
                        throw new IllegalArgumentException("folder " + name + " has no tool " + t);
                    }
                }
                if (r.timeoutMinutes() != null) {
                    timeout = AccessPolicy.checkTimeout(r.timeoutMinutes());
                }
                if (add.isEmpty() && remove.isEmpty() && timeout == null) {
                    throw new IllegalArgumentException("nothing to change");
                }
                if (f.allowedTools().size() - remove.size() + add.size() > AccessPolicy.MAX_TOOLS) {
                    throw new IllegalArgumentException("at most " + AccessPolicy.MAX_TOOLS + " tools");
                }
            }
            default -> {
                Folder f = current.orElseThrow(() -> new IllegalArgumentException("unknown folder: " + name));
                path = f.path();
                if (registry.list().size() <= 1) {
                    throw new IllegalArgumentException("the last folder cannot be removed");
                }
            }
        }
        if (kind != FolderRequest.Kind.REMOVE) {
            warnings.addAll(AccessPolicy.settingsWarnings(Path.of(path), json));
        }
        FolderRequest fr = new FolderRequest(newId(), kind, name, path, add, remove, timeout, r.reason().strip(),
                TaskValidator.cleanSender(r.sender()), warnings, FolderRequest.Status.PENDING, null,
                clock.instant(), null, null, newToken(), null);
        repo.insert(fr);
        log.info("folder request {}: {} {} from '{}'", fr.id(), kind, name, fr.sender());
        for (ApprovalChannel ch : channels) {
            safely(() -> ch.requestFolderApproval(fr), ch, "requestFolderApproval");
        }
        return fr;
    }

    @EventListener
    public void onDecisionAttempt(DecisionAttempt a) {
        decide(a.requestId(), a.approve(), a.channel());
    }

    /** @return true if this call made the decision. An approved request is applied at once. */
    public boolean decide(String id, boolean approve, String channel) {
        if (!repo.tryClaim(id, channel, clock.instant())) {
            return false;
        }
        FolderRequest r = repo.find(id).orElseThrow();
        if (!approve) {
            repo.finish(id, FolderRequest.Status.REJECTED, null);
        } else {
            try {
                apply(r);
                repo.finish(id, FolderRequest.Status.APPLIED, null);
            } catch (Exception e) {
                log.warn("folder request {} could not be applied: {}", id, e.toString());
                repo.finish(id, FolderRequest.Status.FAILED, e.getMessage());
            }
        }
        notify(repo.find(id).orElseThrow());
        return true;
    }

    /** Re-checks everything against the current state: things may have changed while it waited. */
    private void apply(FolderRequest r) throws java.io.IOException {
        Optional<Folder> current = registry.get(r.name());
        switch (r.kind()) {
            case ADD -> {
                if (current.isPresent()) {
                    throw new IllegalStateException("folder " + r.name() + " exists by now");
                }
                Path real = checkedPath(r.path());
                if (!real.toString().equals(r.path())) {
                    throw new IllegalStateException("the path now resolves elsewhere: " + real);
                }
                registry.put(r.name(), new Folder(r.path(), AccessPolicy.checkTools(r.addTools()),
                        Duration.ofMinutes(r.timeoutMinutes())));
            }
            case CHANGE -> {
                Folder f = current.orElseThrow(() -> new IllegalStateException("folder " + r.name() + " is gone"));
                List<String> tools = new ArrayList<>(f.allowedTools());
                tools.removeAll(r.removeTools());
                for (String t : AccessPolicy.checkTools(r.addTools())) {
                    if (!tools.contains(t)) {
                        tools.add(t);
                    }
                }
                registry.put(r.name(), new Folder(f.path(), List.copyOf(tools),
                        r.timeoutMinutes() != null ? Duration.ofMinutes(r.timeoutMinutes()) : f.timeout()));
            }
            case REMOVE -> {
                if (current.isEmpty()) {
                    throw new IllegalStateException("folder " + r.name() + " is gone already");
                }
                registry.remove(r.name());
            }
        }
    }

    @Scheduled(fixedDelayString = "PT30S")
    public void expireStale() {
        Instant now = clock.instant();
        for (FolderRequest r : repo.findPendingCreatedBefore(now.minus(TTL))) {
            if (repo.tryClaim(r.id(), "timeout", now)) {
                repo.finish(r.id(), FolderRequest.Status.EXPIRED, null);
                notify(repo.find(r.id()).orElseThrow());
            }
        }
    }

    private void notify(FolderRequest r) {
        log.info("folder request {}: {}", r.id(), r.status());
        for (ApprovalChannel ch : channels) {
            safely(() -> ch.onFolderRequestUpdated(r), ch, "onFolderRequestUpdated");
        }
    }

    private static FolderRequest.Kind kind(String k) {
        if (k == null) {
            throw new IllegalArgumentException("kind is required: add, change or remove");
        }
        return switch (k.strip().toLowerCase(Locale.ROOT)) {
            case "add" -> FolderRequest.Kind.ADD;
            case "change" -> FolderRequest.Kind.CHANGE;
            case "remove" -> FolderRequest.Kind.REMOVE;
            default -> throw new IllegalArgumentException("kind must be add, change or remove");
        };
    }

    private static void safely(Runnable r, ApprovalChannel ch, String what) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("{}.{} failed: {}", ch.getClass().getSimpleName(), what, e.toString());
        }
    }

    private String newId() {
        String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

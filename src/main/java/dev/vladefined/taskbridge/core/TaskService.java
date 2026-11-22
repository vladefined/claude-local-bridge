package dev.vladefined.taskbridge.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.vladefined.taskbridge.config.BridgeProperties;
import dev.vladefined.taskbridge.config.BridgeProperties.Folder;
import dev.vladefined.taskbridge.runner.ClaudeRunner;
import dev.vladefined.taskbridge.runner.RunResult;
import dev.vladefined.taskbridge.runner.StreamLog;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

/** The task lifecycle: PENDING -> (APPROVED -> RUNNING -> DONE|FAILED) | REJECTED | EXPIRED. */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final String ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final TaskRepository repo;
    private final BridgeProperties props;
    private final List<ApprovalChannel> channels;
    private final ClaudeRunner runner;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    // One task at a time: two Claude sessions continuing the same folder would collide.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "claude-runner"));

    public TaskService(TaskRepository repo, BridgeProperties props, List<ApprovalChannel> channels,
                       ClaudeRunner runner, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.channels = channels;
        this.runner = runner;
        this.clock = clock;
        failInterrupted();
    }

    public Task submit(NewTask request) {
        String text = request.text() == null ? null : request.text().replace("\r\n", "\n");
        NewTask req = new NewTask(request.folder(), text, request.mode(), request.sessionId(),
                TaskValidator.cleanSender(request.sender()));
        TaskValidator.validate(req, props.maxTaskChars());
        if (!props.folders().containsKey(req.folder())) {
            throw new IllegalArgumentException("unknown folder: " + req.folder());
        }

        Task task = new Task(newId(), req.folder(), req.mode(), req.sessionId(), req.text(), req.sender(),
                sha256(req.text()), TaskStatus.PENDING, null, clock.instant(), null, null,
                newToken(), null, null, null, false, null, null);
        repo.insert(task);
        log.info("task {} received from '{}' for folder {}", task.id(), task.sender(), task.folder());
        for (ApprovalChannel ch : channels) {
            safely(() -> ch.requestApproval(task), ch, "requestApproval");
        }
        return task;
    }

    public Optional<Task> find(String id) {
        return repo.find(id);
    }

    /**
     * What the agent is doing / did: the last steps of its event stream.
     *
     * @param lastActivity when the stream file last changed (null if there is none yet)
     */
    public record Progress(Task task, List<String> steps, Instant lastActivity) {
    }

    public static final int PROGRESS_STEPS = 15;

    public Optional<Progress> progress(String id) {
        return repo.find(id).map(t -> {
            Path stream = switch (t.status()) {
                case RUNNING -> runner.runningStdout(t);
                case DONE, FAILED -> ClaudeRunner.sibling(t, ClaudeRunner.STDOUT_SUFFIX);
                default -> null;
            };
            if (stream == null || !Files.isRegularFile(stream)) {
                return new Progress(t, List.of(), null);
            }
            try {
                return new Progress(t, StreamLog.recentSteps(stream, PROGRESS_STEPS, json),
                        Files.getLastModifiedTime(stream).toInstant());
            } catch (IOException e) {
                log.warn("task {}: cannot read progress: {}", id, e.toString());
                return new Progress(t, List.of(), null);
            }
        });
    }

    @EventListener
    public void onDecisionAttempt(DecisionAttempt a) {
        decide(a.taskId(), a.approve(), a.channel());
    }

    /** @return true if this call made the decision, false if the task was already decided. */
    public boolean decide(String id, boolean approve, String channel) {
        TaskStatus to = approve ? TaskStatus.APPROVED : TaskStatus.REJECTED;
        if (!repo.tryDecide(id, to, channel, clock.instant())) {
            return false;
        }
        Task decided = repo.find(id).orElseThrow();
        log.info("task {} {} via {}", id, to, channel);
        notifyDecided(decided);
        if (approve) {
            executor.submit(() -> run(id));
        }
        return true;
    }

    @Scheduled(fixedDelayString = "PT30S")
    public void expireStale() {
        Instant now = clock.instant();
        for (Task t : repo.findPendingCreatedBefore(now.minus(props.approvalTtl()))) {
            if (repo.tryDecide(t.id(), TaskStatus.EXPIRED, "timeout", now)) {
                log.info("task {} expired", t.id());
                notifyDecided(repo.find(t.id()).orElseThrow());
            }
        }
    }

    void run(String id) {
        if (!repo.markRunning(id)) {
            return;
        }
        Task task = repo.find(id).orElseThrow();
        Folder folder = props.folders().get(task.folder());
        RunResult r;
        try {
            r = runner.run(task, folder);
        } catch (RuntimeException e) {
            log.error("task {} crashed", id, e);
            r = new RunResult(false, "Bridge error: " + e.getMessage(), null, null);
        }
        // The DB and Telegram get a short version; the full output stays in a file on disk.
        Summary.Split split = Summary.split(r.text());
        String text = split.body();
        int max = props.maxResultChars();
        boolean truncated = text.length() > max;
        String shown = truncated ? text.substring(0, max) : text;
        repo.finish(id, r.ok() ? TaskStatus.DONE : TaskStatus.FAILED, shown, truncated,
                r.outputFile() == null ? null : r.outputFile().toString(), split.summary(), r.sessionId(),
                clock.instant());
        Task finished = repo.find(id).orElseThrow();
        for (ApprovalChannel ch : channels) {
            safely(() -> ch.onFinished(finished), ch, "onFinished");
        }
    }

    /** Never re-run something after a restart without asking again. */
    private void failInterrupted() {
        for (Task t : repo.findInterrupted()) {
            repo.finish(t.id(), TaskStatus.FAILED, "Interrupted by a bridge restart; not re-run.",
                    false, null, null, null, clock.instant());
        }
    }

    private void notifyDecided(Task task) {
        for (ApprovalChannel ch : channels) {
            safely(() -> ch.onDecided(task), ch, "onDecided");
        }
    }

    private static void safely(Runnable r, ApprovalChannel ch, String what) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("{}.{} failed: {}", ch.getClass().getSimpleName(), what, e.toString());
        }
    }

    private String newId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String sha256(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

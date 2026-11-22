package dev.vladefined.taskbridge.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.vladefined.taskbridge.core.HmacVerifier;
import dev.vladefined.taskbridge.core.HmacVerifier.AuthException;
import dev.vladefined.taskbridge.core.NewMessage;
import dev.vladefined.taskbridge.core.NewTask;
import dev.vladefined.taskbridge.core.TaskMessage;
import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.TaskService;
import dev.vladefined.taskbridge.runner.ClaudeRunner;

/**
 * The only surface reachable from outside (through the tunnel).
 * The body is read as raw bytes so the signature is checked over exactly what was sent.
 */
@RestController
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);
    static final String TS = "X-Bridge-Timestamp";
    static final String NONCE = "X-Bridge-Nonce";
    static final String SIG = "X-Bridge-Signature";

    private final TaskService tasks;
    private final HmacVerifier hmac;
    private final ObjectMapper json;

    public TaskController(TaskService tasks, HmacVerifier hmac, ObjectMapper json) {
        this.tasks = tasks;
        this.hmac = hmac;
        this.json = json.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
    }

    @PostMapping("/tasks")
    public ResponseEntity<Map<String, Object>> submit(
            @RequestHeader(value = TS, required = false) String ts,
            @RequestHeader(value = NONCE, required = false) String nonce,
            @RequestHeader(value = SIG, required = false) String sig,
            @RequestBody(required = false) byte[] body) throws IOException {
        byte[] raw = body == null ? new byte[0] : body;
        hmac.verify("POST", "/tasks", ts, nonce, sig, raw);
        NewTask req = json.readValue(raw, NewTask.class);
        Task t = tasks.submit(req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(t));
    }

    @GetMapping("/tasks/{id}")
    public ResponseEntity<Map<String, Object>> get(
            @PathVariable String id,
            @RequestHeader(value = TS, required = false) String ts,
            @RequestHeader(value = NONCE, required = false) String nonce,
            @RequestHeader(value = SIG, required = false) String sig) {
        hmac.verify("GET", "/tasks/" + id, ts, nonce, sig, new byte[0]);
        return tasks.find(id)
                .map(t -> ResponseEntity.ok(view(t, tasks.progress(id).map(p -> p.messages()).orElse(java.util.List.of()))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add a message to a running task. Body: {@code {"text": "...", "cursor": <from progress>,
     * "sender": "..."}}. The owner approves it like a task; then it is handed to the agent.
     */
    @PostMapping("/tasks/{id}/messages")
    public ResponseEntity<Map<String, Object>> addMessage(
            @PathVariable String id,
            @RequestHeader(value = TS, required = false) String ts,
            @RequestHeader(value = NONCE, required = false) String nonce,
            @RequestHeader(value = SIG, required = false) String sig,
            @RequestBody(required = false) byte[] body) throws IOException {
        byte[] raw = body == null ? new byte[0] : body;
        hmac.verify("POST", "/tasks/" + id + "/messages", ts, nonce, sig, raw);
        NewMessage req = json.readValue(raw, NewMessage.class);
        if (tasks.find(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        TaskMessage m = tasks.submitMessage(id, req);
        Map<String, Object> v = messageView(m);
        v.put("taskId", m.taskId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(v);
    }

    /** Full result text when {@code resultTruncated} is true. */
    @GetMapping(value = "/tasks/{id}/output", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<Resource> output(
            @PathVariable String id,
            @RequestHeader(value = TS, required = false) String ts,
            @RequestHeader(value = NONCE, required = false) String nonce,
            @RequestHeader(value = SIG, required = false) String sig) {
        hmac.verify("GET", "/tasks/" + id + "/output", ts, nonce, sig, new byte[0]);
        return tasks.find(id)
                .filter(t -> t.outputPath() != null && Files.isRegularFile(Path.of(t.outputPath())))
                .map(t -> ResponseEntity.ok((Resource) new FileSystemResource(t.outputPath())))
                .orElse(ResponseEntity.notFound().build());
    }

    /** The agent's last steps (tool calls and messages): live while RUNNING, final afterwards. */
    @GetMapping("/tasks/{id}/progress")
    public ResponseEntity<Map<String, Object>> progress(
            @PathVariable String id,
            @RequestHeader(value = TS, required = false) String ts,
            @RequestHeader(value = NONCE, required = false) String nonce,
            @RequestHeader(value = SIG, required = false) String sig) {
        hmac.verify("GET", "/tasks/" + id + "/progress", ts, nonce, sig, new byte[0]);
        return tasks.progress(id).map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.task().id());
            m.put("status", p.task().status());
            m.put("decidedBy", p.task().decidedBy());
            m.put("lastActivity", p.lastActivity() == null ? null : p.lastActivity().toString());
            m.put("cursor", p.cursor());
            m.put("steps", p.steps());
            m.put("messages", p.messages().stream().map(TaskController::messageView).toList());
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Readable transcript of the whole run (every message and tool call), after it finished. */
    @GetMapping(value = "/tasks/{id}/log", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<Resource> runLog(
            @PathVariable String id,
            @RequestHeader(value = TS, required = false) String ts,
            @RequestHeader(value = NONCE, required = false) String nonce,
            @RequestHeader(value = SIG, required = false) String sig) {
        hmac.verify("GET", "/tasks/" + id + "/log", ts, nonce, sig, new byte[0]);
        return tasks.find(id)
                .map(t -> ClaudeRunner.sibling(t, ClaudeRunner.LOG_SUFFIX))
                .filter(Files::isRegularFile)
                .map(p -> ResponseEntity.ok((Resource) new FileSystemResource(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    private static Map<String, Object> messageView(TaskMessage m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", m.id());
        v.put("status", m.status());
        v.put("decidedBy", m.decidedBy());
        v.put("sha256", m.sha256());
        return v;
    }

    private static Map<String, Object> view(Task t, java.util.List<TaskMessage> msgs) {
        Map<String, Object> m = view(t);
        if (!msgs.isEmpty()) {
            m.put("messages", msgs.stream().map(TaskController::messageView).toList());
        }
        return m;
    }

    private static Map<String, Object> view(Task t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("status", t.status());
        m.put("sha256", t.sha256());
        m.put("decidedBy", t.decidedBy());
        if (!t.extraToolList().isEmpty()) {
            m.put("extraTools", t.extraToolList());
        }
        m.put("summary", t.summary());
        m.put("result", t.result());
        m.put("resultTruncated", t.resultTruncated());
        m.put("sessionId", t.sessionId());
        return m;
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, String>> auth(AuthException e) {
        // Log the reason locally, tell the caller nothing useful.
        log.warn("rejected request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    @ExceptionHandler(TaskService.Conflict.class)
    ResponseEntity<Map<String, Object>> conflict(TaskService.Conflict e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getMessage());
        if (e.stepsBehind() >= 0) {
            m.put("stepsBehind", e.stepsBehind() == Integer.MAX_VALUE ? null : e.stepsBehind());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    ResponseEntity<Map<String, String>> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}

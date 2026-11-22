package dev.vladefined.taskbridge.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import dev.vladefined.taskbridge.core.NewTask;
import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.TaskService;

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
                .map(t -> ResponseEntity.ok(view(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    private static Map<String, Object> view(Task t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("status", t.status());
        m.put("sha256", t.sha256());
        m.put("decidedBy", t.decidedBy());
        m.put("result", t.result());
        m.put("sessionId", t.sessionId());
        return m;
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, String>> auth(AuthException e) {
        // Log the reason locally, tell the caller nothing useful.
        log.warn("rejected request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    ResponseEntity<Map<String, String>> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}

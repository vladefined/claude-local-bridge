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

import dev.vladefined.taskbridge.access.FolderAccessService;
import dev.vladefined.taskbridge.access.FolderAccessService.NewFolderRequest;
import dev.vladefined.taskbridge.access.FolderRegistry;
import dev.vladefined.taskbridge.access.FolderRequest;
import dev.vladefined.taskbridge.core.HmacVerifier;
import dev.vladefined.taskbridge.core.HmacVerifier.AuthException;

/**
 * Which folders exist, and requests to change them. Signed like the task API; a
 * request only becomes a change after the owner approves it.
 */
@RestController
public class FolderController {

    private static final Logger log = LoggerFactory.getLogger(FolderController.class);

    private final FolderRegistry registry;
    private final FolderAccessService access;
    private final HmacVerifier hmac;
    private final ObjectMapper json;

    public FolderController(FolderRegistry registry, FolderAccessService access, HmacVerifier hmac,
                            ObjectMapper json) {
        this.registry = registry;
        this.access = access;
        this.hmac = hmac;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @GetMapping("/folders")
    public Map<String, Object> folders(
            @RequestHeader(value = TaskController.TS, required = false) String ts,
            @RequestHeader(value = TaskController.NONCE, required = false) String nonce,
            @RequestHeader(value = TaskController.SIG, required = false) String sig) {
        hmac.verify("GET", "/folders", ts, nonce, sig, new byte[0]);
        return Map.of("folders", registry.list().stream().map(FolderRegistry::view).toList());
    }

    /**
     * Body: {@code {"kind": "add|change|remove", "name", "path", "addTools": [], "removeTools": [],
     * "timeoutMinutes", "reason", "sender"}}.
     */
    @PostMapping("/folders/requests")
    public ResponseEntity<Map<String, Object>> request(
            @RequestHeader(value = TaskController.TS, required = false) String ts,
            @RequestHeader(value = TaskController.NONCE, required = false) String nonce,
            @RequestHeader(value = TaskController.SIG, required = false) String sig,
            @RequestBody(required = false) byte[] body) throws IOException {
        byte[] raw = body == null ? new byte[0] : body;
        hmac.verify("POST", "/folders/requests", ts, nonce, sig, raw);
        FolderRequest r = access.submit(json.readValue(raw, NewFolderRequest.class));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view(r));
    }

    @GetMapping("/folders/requests/{id}")
    public ResponseEntity<Map<String, Object>> requestStatus(
            @PathVariable String id,
            @RequestHeader(value = TaskController.TS, required = false) String ts,
            @RequestHeader(value = TaskController.NONCE, required = false) String nonce,
            @RequestHeader(value = TaskController.SIG, required = false) String sig) {
        hmac.verify("GET", "/folders/requests/" + id, ts, nonce, sig, new byte[0]);
        return access.find(id).map(r -> ResponseEntity.ok(view(r))).orElse(ResponseEntity.notFound().build());
    }

    static Map<String, Object> view(FolderRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("kind", r.kind().name().toLowerCase(java.util.Locale.ROOT));
        m.put("name", r.name());
        m.put("path", r.path());
        m.put("addTools", r.addTools());
        m.put("removeTools", r.removeTools());
        m.put("timeoutMinutes", r.timeoutMinutes());
        m.put("warnings", r.warnings());
        m.put("status", r.status());
        m.put("decidedBy", r.decidedBy());
        m.put("error", r.error());
        return m;
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, String>> auth(AuthException e) {
        log.warn("rejected request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
    }

    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    ResponseEntity<Map<String, String>> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}

package dev.vladefined.taskbridge.desktop;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.vladefined.taskbridge.config.BridgeProperties;
import dev.vladefined.taskbridge.core.ApprovalChannel;
import dev.vladefined.taskbridge.core.DecisionAttempt;
import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.MessageDecisionAttempt;
import dev.vladefined.taskbridge.core.MessageStatus;
import dev.vladefined.taskbridge.core.TaskMessage;
import dev.vladefined.taskbridge.core.TaskMessageRepository;
import dev.vladefined.taskbridge.core.TaskRepository;
import dev.vladefined.taskbridge.core.TaskStatus;
import dev.vladefined.taskbridge.core.UiText;

/**
 * Approval on the PC itself: a Windows toast whose buttons decide in the background
 * (through {@link ProtocolHandler}) or, as a fallback, open a local page.
 *
 * <p>The local page runs on its OWN port, bound to 127.0.0.1, separate from the
 * API port. The tunnel forwards to the API port from localhost, so a "loopback
 * only" check on the API port would let the internet through; a separate port the
 * tunnel never points at does not. Each task also carries a random 256-bit token,
 * and the Host header is checked against DNS-rebinding tricks from a browser.
 */
@Component
@ConditionalOnProperty(name = "bridge.desktop.enabled", havingValue = "true")
public class DesktopChannel implements ApprovalChannel, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DesktopChannel.class);
    public static final String CHANNEL = "desktop";

    private final TaskRepository repo;
    private final TaskMessageRepository messages;
    private final ApplicationEventPublisher events;
    private final WindowsToaster toaster;
    private final UiText ui;
    private final int port;
    private final int previewChars;
    private final boolean windows;
    private final boolean wantProtocol;
    private volatile boolean protocolReady;
    private HttpServer server;

    @Autowired
    public DesktopChannel(BridgeProperties props, TaskRepository repo, TaskMessageRepository messages,
                          ApplicationEventPublisher events, UiText ui) {
        this(props, repo, messages, events, ui, new WindowsToaster(),
                System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
    }

    DesktopChannel(BridgeProperties props, TaskRepository repo, TaskMessageRepository messages,
                   ApplicationEventPublisher events, UiText ui, WindowsToaster toaster, boolean windows) {
        this.ui = ui;
        this.messages = messages;
        this.repo = repo;
        this.events = events;
        this.toaster = toaster;
        this.port = props.desktop().port();
        this.previewChars = props.desktop().toastPreviewChars();
        this.windows = windows;
        this.wantProtocol = props.desktop().protocolButtons();
        if (!windows) {
            log.warn("Desktop toasts need Windows; the local approval page still works on port {}", port);
        }
    }

    String pageUrl(Task t) {
        return "http://127.0.0.1:" + port + "/t/" + t.desktopToken();
    }

    @Override
    public void requestApproval(Task t) {
        if (windows) {
            toaster.show(t.id(), ToastXml.build(ui, t, pageUrl(t), protocolReady, previewChars));
        }
    }

    @Override
    public void onDecided(Task t) {
        if (windows) {
            toaster.remove(t.id());
        }
    }

    @Override
    public void onFinished(Task t) {
        if (windows) {
            toaster.show(t.id(), ToastXml.finished(ui, t));
        }
    }

    String pageUrl(TaskMessage m) {
        return "http://127.0.0.1:" + port + "/t/" + m.desktopToken();
    }

    @Override
    public void requestMessageApproval(Task t, TaskMessage m, java.util.List<String> since,
                                       java.util.List<String> recent) {
        if (windows) {
            toaster.show(m.id(), ToastXml.message(ui, t, m, pageUrl(m), protocolReady, previewChars));
        }
    }

    @Override
    public void onMessageUpdated(Task t, TaskMessage m) {
        if (windows && m.status() != MessageStatus.APPROVED) {
            toaster.remove(m.id());
        }
    }

    // ---- local page ----

    void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String host = ex.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + port).equals(host) && !("localhost:" + port).equals(host)) {
                send(ex, 403, page(ui.t("page.forbidden"), "<p>" + h(ui.t("page.bad-host")) + "</p>"));
                return;
            }
            String[] parts = ex.getRequestURI().getPath().split("/");
            // ["", "t", token] or ["", "t", token, action]
            if (parts.length < 3 || !"t".equals(parts[1])) {
                send(ex, 404, page(ui.t("page.not-found"), ""));
                return;
            }
            Optional<Task> found = repo.findByDesktopToken(parts[2]);
            if (found.isEmpty()) {
                Optional<TaskMessage> msg = messages == null ? Optional.empty() : messages.findByDesktopToken(parts[2]);
                if (msg.isPresent()) {
                    TaskMessage m = msg.get();
                    if (parts.length == 4 && m.status() == MessageStatus.PENDING) {
                        boolean approve = switch (parts[3]) {
                            case "approve" -> true;
                            case "reject" -> false;
                            default -> throw new IllegalArgumentException("bad action");
                        };
                        events.publishEvent(new MessageDecisionAttempt(m.id(), approve, CHANNEL));
                        m = messages.find(m.id()).orElseThrow();
                    }
                    send(ex, 200, render(m));
                    return;
                }
                send(ex, 404, page(ui.t("page.not-found"), "<p>" + h(ui.t("page.bad-link")) + "</p>"));
                return;
            }
            Task t = found.get();
            if (parts.length == 4 && t.status() == TaskStatus.PENDING) {
                boolean approve = switch (parts[3]) {
                    case "approve" -> true;
                    case "reject" -> false;
                    default -> throw new IllegalArgumentException("bad action");
                };
                events.publishEvent(new DecisionAttempt(t.id(), approve, CHANNEL));
                t = repo.find(t.id()).orElseThrow();
            }
            send(ex, 200, render(t));
        } catch (IllegalArgumentException e) {
            send(ex, 400, page(ui.t("page.error"), ""));
        }
    }

    String render(Task t) {
        String body = "<p class=meta>" + h(ui.t("task.folder")) + ": <b>" + h(t.folder()) + "</b> · "
                + h(ui.t("page.from")) + ": " + h(t.sender())
                + " · SHA-256 " + h(t.shortHash()) + "</p>"
                + "<pre>" + h(t.text()) + "</pre>";
        if (t.status() == TaskStatus.PENDING) {
            body += "<form method=post action='" + h(pathFor(t)) + "/approve'><button class=ok>" + h(ui.t("toast.approve")) + "</button></form>"
                    + "<form method=post action='" + h(pathFor(t)) + "/reject'><button class=no>" + h(ui.t("toast.reject")) + "</button></form>";
        } else {
            body += "<p class=status>" + h(ui.t("page.status")) + ": " + h(t.status().name())
                    + (t.decidedBy() != null ? " (" + h(t.decidedBy()) + ")" : "") + "</p>";
        }
        return page(ui.t("task.title", t.id()), body);
    }

    String render(TaskMessage m) {
        Task t = repo.find(m.taskId()).orElse(null);
        String body = "<p class=meta>" + h(ui.t("task.title", m.taskId()))
                + (t != null ? " · " + h(ui.t("task.folder")) + ": <b>" + h(t.folder()) + "</b>" : "")
                + " · " + h(ui.t("page.from")) + ": " + h(m.sender())
                + " · SHA-256 " + h(m.shortHash()) + "</p>"
                + "<p class=meta>" + h(m.stepsBehind() == 0 ? ui.t("msg.since.none")
                        : ui.t("msg.since", m.stepsBehind()).replaceAll(":$", ".")) + "</p>"
                + "<pre>" + h(m.text()) + "</pre>";
        if (m.status() == MessageStatus.PENDING) {
            String path = "/t/" + m.desktopToken();
            body += "<form method=post action='" + h(path) + "/approve'><button class=ok>" + h(ui.t("toast.send")) + "</button></form>"
                    + "<form method=post action='" + h(path) + "/reject'><button class=no>" + h(ui.t("toast.reject")) + "</button></form>";
        } else {
            body += "<p class=status>" + h(ui.t("page.status")) + ": " + h(m.status().name())
                    + (m.decidedBy() != null ? " (" + h(m.decidedBy()) + ")" : "") + "</p>";
        }
        return page(ui.t("toast.msg.title", m.taskId()), body);
    }

    private static String pathFor(Task t) {
        return "/t/" + t.desktopToken();
    }

    private String page(String title, String body) {
        return "<!doctype html><html lang=" + ui.language() + "><meta charset=utf-8><title>" + h(title) + "</title>"
                + "<style>body{font:15px system-ui;max-width:760px;margin:24px auto;padding:0 16px}"
                + "pre{white-space:pre-wrap;background:#f4f4f4;padding:12px;border-radius:6px}"
                + "form{display:inline}button{font-size:15px;padding:8px 18px;margin-right:8px;border:0;border-radius:6px;cursor:pointer}"
                + ".ok{background:#1a7f37;color:#fff}.no{background:#cf222e;color:#fff}.meta{color:#555}</style>"
                + "<h2>" + h(title) + "</h2>" + body;
    }

    static String h(String s) {
        return ToastXml.esc(s == null ? "" : s);
    }

    private static void send(HttpExchange ex, int code, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        var hd = ex.getResponseHeaders();
        hd.set("Content-Type", "text/html; charset=utf-8");
        hd.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'");
        hd.set("X-Frame-Options", "DENY");
        hd.set("Referrer-Policy", "no-referrer");
        hd.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    @Override
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            throw new IllegalStateException("cannot bind local approval page on port " + port, e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("local approval page on http://127.0.0.1:{}", port);
        if (windows && wantProtocol) {
            try {
                log.info("toast buttons handled in the background by {}", ProtocolHandler.register(port));
                protocolReady = true;
            } catch (IOException | RuntimeException e) {
                log.warn("could not register the claude-bridge: handler ({}); toast buttons will open the page",
                        e.toString());
            }
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Override
    public boolean isRunning() {
        return server != null;
    }
}

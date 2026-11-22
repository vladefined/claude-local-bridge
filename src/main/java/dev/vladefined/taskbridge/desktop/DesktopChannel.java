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
import dev.vladefined.taskbridge.core.TaskRepository;
import dev.vladefined.taskbridge.core.TaskStatus;

/**
 * Approval on the PC itself: a Windows toast whose buttons open a local page.
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
    private final ApplicationEventPublisher events;
    private final WindowsToaster toaster;
    private final int port;
    private final int previewChars;
    private final boolean windows;
    private HttpServer server;

    @Autowired
    public DesktopChannel(BridgeProperties props, TaskRepository repo, ApplicationEventPublisher events) {
        this(props, repo, events, new WindowsToaster(),
                System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
    }

    DesktopChannel(BridgeProperties props, TaskRepository repo, ApplicationEventPublisher events,
                   WindowsToaster toaster, boolean windows) {
        this.repo = repo;
        this.events = events;
        this.toaster = toaster;
        this.port = props.desktop().port();
        this.previewChars = props.desktop().toastPreviewChars();
        this.windows = windows;
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
            toaster.show(t.id(), ToastXml.build(t, pageUrl(t), previewChars));
        }
    }

    @Override
    public void onDecided(Task t) {
        if (windows) {
            toaster.remove(t.id());
        }
    }

    // ---- local page ----

    void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String host = ex.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + port).equals(host) && !("localhost:" + port).equals(host)) {
                send(ex, 403, page("Запрещено", "<p>Неверный адрес.</p>"));
                return;
            }
            String[] parts = ex.getRequestURI().getPath().split("/");
            // ["", "t", token] or ["", "t", token, action]
            if (parts.length < 3 || !"t".equals(parts[1])) {
                send(ex, 404, page("Не найдено", ""));
                return;
            }
            Optional<Task> found = repo.findByDesktopToken(parts[2]);
            if (found.isEmpty()) {
                send(ex, 404, page("Не найдено", "<p>Ссылка недействительна.</p>"));
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
            send(ex, 400, page("Ошибка", ""));
        }
    }

    String render(Task t) {
        String body = "<p class=meta>Папка: <b>" + h(t.folder()) + "</b> · от: " + h(t.sender())
                + " · SHA-256 " + h(t.shortHash()) + "</p>"
                + "<pre>" + h(t.text()) + "</pre>";
        if (t.status() == TaskStatus.PENDING) {
            body += "<form method=post action='" + h(pathFor(t)) + "/approve'><button class=ok>Подтвердить</button></form>"
                    + "<form method=post action='" + h(pathFor(t)) + "/reject'><button class=no>Отклонить</button></form>";
        } else {
            body += "<p class=status>Статус: " + h(t.status().name())
                    + (t.decidedBy() != null ? " (" + h(t.decidedBy()) + ")" : "") + "</p>";
        }
        return page("Задача " + t.id(), body);
    }

    private static String pathFor(Task t) {
        return "/t/" + t.desktopToken();
    }

    private static String page(String title, String body) {
        return "<!doctype html><meta charset=utf-8><title>" + h(title) + "</title>"
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

package dev.vladefined.taskbridge.desktop;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.TaskMessage;
import dev.vladefined.taskbridge.core.UiText;

/** Builds the Windows toast XML. Every piece of task data is XML-escaped. */
public final class ToastXml {

    /** Scheme of the background handler registered by {@link ProtocolHandler}. */
    public static final String SCHEME = "claude-bridge";
    static final int RESULT_PREVIEW_CHARS = 200;

    private ToastXml() {
    }

    /**
     * The approval toast.
     *
     * @param pageUrl local page, e.g. http://127.0.0.1:8766/t/TOKEN (clicking the toast body opens it)
     * @param protocolButtons true: buttons call {@code claude-bridge:approve/TOKEN}, handled in the
     *                        background with no browser; false: buttons open the local page
     * @param previewChars if the text is longer, "Approve" is replaced by "Open": you only
     *                     approve from the toast what the toast can show in full.
     */
    public static String build(UiText ui, Task t, String pageUrl, boolean protocolButtons, int previewChars) {
        boolean fits = t.text().length() <= previewChars;
        String preview = fits ? t.text() : t.text().substring(0, previewChars) + "…";
        String approve = fits
                ? action(ui.t("toast.approve"), decision(t, pageUrl, protocolButtons, "approve"))
                : action(ui.t("toast.open"), pageUrl);
        return "<toast scenario=\"reminder\" activationType=\"protocol\" launch=\"" + esc(pageUrl) + "\">"
                + "<visual><binding template=\"ToastGeneric\">"
                + "<text>" + esc(ui.t("task.title", t.id()) + " → " + t.folder()) + "</text>"
                + "<text>" + esc(preview) + "</text>"
                + "<text placement=\"attribution\">" + esc(ui.t("toast.from", t.sender()) + " · " + t.shortHash()) + "</text>"
                + "</binding></visual>"
                + "<actions>" + approve
                + action(ui.t("toast.reject"), decision(t, pageUrl, protocolButtons, "reject")) + "</actions>"
                + "<audio src=\"ms-winsoundevent:Notification.Reminder\"/>"
                + "</toast>";
    }

    /** Approval toast for a message to a running task. Long text: "Open" instead of "Send", as for tasks. */
    public static String message(UiText ui, Task t, TaskMessage m, String pageUrl, boolean protocolButtons,
                                 int previewChars) {
        boolean fits = m.text().length() <= previewChars;
        String preview = fits ? m.text() : m.text().substring(0, previewChars) + "…";
        String send = fits
                ? action(ui.t("toast.send"), decision(m.desktopToken(), pageUrl, protocolButtons, "approve"))
                : action(ui.t("toast.open"), pageUrl);
        return "<toast scenario=\"reminder\" activationType=\"protocol\" launch=\"" + esc(pageUrl) + "\">"
                + "<visual><binding template=\"ToastGeneric\">"
                + "<text>" + esc(ui.t("toast.msg.title", t.id()) + " → " + t.folder()) + "</text>"
                + "<text>" + esc(preview) + "</text>"
                + "<text placement=\"attribution\">" + esc(ui.t("toast.from", m.sender()) + " · " + m.shortHash()) + "</text>"
                + "</binding></visual>"
                + "<actions>" + send
                + action(ui.t("toast.reject"), decision(m.desktopToken(), pageUrl, protocolButtons, "reject")) + "</actions>"
                + "<audio src=\"ms-winsoundevent:Notification.Reminder\"/>"
                + "</toast>";
    }

    /** The toast after a run: the agent's summary (or the start of its answer). Click opens the full output. */
    public static String finished(UiText ui, Task t) {
        String title = ui.t(t.status() == dev.vladefined.taskbridge.core.TaskStatus.DONE ? "result.done" : "result.failed",
                t.id()) + " → " + t.folder();
        String text;
        if (t.summary() != null) {
            text = t.summary();
        } else {
            String r = t.result() == null || t.result().isBlank() ? ui.t("result.empty") : t.result().strip();
            text = r.length() > RESULT_PREVIEW_CHARS ? r.substring(0, RESULT_PREVIEW_CHARS) + "…" : r;
        }
        String launch = "";
        if (t.outputPath() != null && Files.isRegularFile(Path.of(t.outputPath()))) {
            launch = " activationType=\"protocol\" launch=\""
                    + esc(Path.of(t.outputPath()).toUri().toString()) + "\"";
        }
        return "<toast" + launch + ">"
                + "<visual><binding template=\"ToastGeneric\">"
                + "<text>" + esc(title) + "</text>"
                + "<text>" + esc(text) + "</text>"
                + (launch.isEmpty() ? "" : "<text placement=\"attribution\">" + esc(ui.t("toast.open-output")) + "</text>")
                + "</binding></visual>"
                + "</toast>";
    }

    private static String decision(Task t, String pageUrl, boolean protocolButtons, String what) {
        return decision(t.desktopToken(), pageUrl, protocolButtons, what);
    }

    private static String decision(String token, String pageUrl, boolean protocolButtons, String what) {
        return protocolButtons ? SCHEME + ":" + what + "/" + token : pageUrl + "/" + what;
    }

    private static String action(String label, String url) {
        return "<action content=\"" + esc(label) + "\" activationType=\"protocol\" arguments=\"" + esc(url) + "\"/>";
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

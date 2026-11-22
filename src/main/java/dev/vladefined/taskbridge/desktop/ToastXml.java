package dev.vladefined.taskbridge.desktop;

import dev.vladefined.taskbridge.core.Task;

/** Builds the Windows toast XML. Every piece of task data is XML-escaped. */
public final class ToastXml {

    private ToastXml() {
    }

    /**
     * @param baseUrl local page base, e.g. http://127.0.0.1:8766/t/TOKEN
     * @param previewChars if the text is longer, "Approve" is replaced by "Open": you only
     *                     approve from the toast what the toast can show in full.
     */
    public static String build(Task t, String baseUrl, int previewChars) {
        boolean fits = t.text().length() <= previewChars;
        String preview = fits ? t.text() : t.text().substring(0, previewChars) + "…";
        String approve = fits
                ? action("Подтвердить", baseUrl + "/approve")
                : action("Открыть полностью", baseUrl);
        return "<toast scenario=\"reminder\" activationType=\"protocol\" launch=\"" + esc(baseUrl) + "\">"
                + "<visual><binding template=\"ToastGeneric\">"
                + "<text>" + esc("Задача " + t.id() + " → " + t.folder()) + "</text>"
                + "<text>" + esc(preview) + "</text>"
                + "<text placement=\"attribution\">" + esc("от: " + t.sender() + " · " + t.shortHash()) + "</text>"
                + "</binding></visual>"
                + "<actions>" + approve + action("Отклонить", baseUrl + "/reject") + "</actions>"
                + "<audio src=\"ms-winsoundevent:Notification.Reminder\"/>"
                + "</toast>";
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

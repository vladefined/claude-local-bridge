package dev.vladefined.taskbridge.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shows and removes toasts through PowerShell and the WinRT notification API.
 * Data never goes into the script as text: the XML is embedded as base64 and the
 * tag is our own [a-z0-9] id, so nothing from a task can become PowerShell code.
 */
public class WindowsToaster {

    // Windows PowerShell's own AppUserModelID: lets an unpackaged app show toasts.
    static final String APP_ID = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe";
    static final String GROUP = "task-bridge";

    private static final String PRELUDE = """
            [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
            [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
            """;

    public void show(String tag, String toastXml) {
        requireSafeTag(tag);
        String b64 = Base64.getEncoder().encodeToString(toastXml.getBytes(StandardCharsets.UTF_8));
        String script = PRELUDE
                + "$x = New-Object Windows.Data.Xml.Dom.XmlDocument\n"
                + "$x.LoadXml([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + b64 + "')))\n"
                + "$t = New-Object Windows.UI.Notifications.ToastNotification $x\n"
                + "$t.Tag = '" + tag + "'; $t.Group = '" + GROUP + "'\n"
                + "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('" + APP_ID + "').Show($t)\n";
        run(script);
    }

    public void remove(String tag) {
        requireSafeTag(tag);
        run(PRELUDE + "[Windows.UI.Notifications.ToastNotificationManager]::History.Remove('"
                + tag + "', '" + GROUP + "', '" + APP_ID + "')\n");
    }

    static String encodedCommand(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static void requireSafeTag(String tag) {
        if (!tag.matches("[a-z0-9]{1,32}")) {
            throw new IllegalArgumentException("unsafe toast tag");
        }
    }

    private static void run(String script) {
        try {
            Process p = new ProcessBuilder(List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedCommand(script)))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(20, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("powershell timed out");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("powershell exit " + p.exitValue());
            }
        } catch (IOException e) {
            throw new IllegalStateException("powershell: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

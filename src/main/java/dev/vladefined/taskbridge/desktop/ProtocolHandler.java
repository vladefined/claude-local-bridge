package dev.vladefined.taskbridge.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Registers the {@code claude-bridge:} URL scheme for the current user so toast
 * buttons can decide in the background instead of opening a browser tab.
 *
 * <p>Windows hands {@code claude-bridge:approve/TOKEN} to a small PowerShell script,
 * started through {@code conhost --headless} so no console window flashes. The script
 * accepts only {@code approve|reject} plus a token of the exact shape we issue, and
 * POSTs it to the local approval page on 127.0.0.1. Any page could try to open such a
 * URL, but without the task's 256-bit token it decides nothing.
 *
 * <p>Registration is under HKCU (no admin) and is rewritten at every start, so the
 * port in the script always matches the running bridge.
 */
public final class ProtocolHandler {

    private ProtocolHandler() {
    }

    static String script(int port) {
        return """
                # claude-local-bridge: background handler for toast buttons. Rewritten at every bridge start.
                param([string]$Uri)
                if ($Uri -cnotmatch '^claude-bridge:/*(approve|reject)/([A-Za-z0-9_-]{43})/?$') { exit 2 }
                try {
                    Invoke-WebRequest -UseBasicParsing -Method Post -TimeoutSec 10 `
                        -Uri ('http://127.0.0.1:%d/t/' + $Matches[2] + '/' + $Matches[1]) | Out-Null
                } catch { exit 3 }
                """.formatted(port);
    }

    /** @return the handler script path; throws if registration failed. */
    public static Path register(int port) throws IOException {
        String base = System.getenv("LOCALAPPDATA");
        Path dir = (base != null ? Path.of(base) : Path.of(System.getProperty("user.home")))
                .resolve("claude-local-bridge");
        Files.createDirectories(dir);
        Path ps1 = dir.resolve("decide.ps1");
        Files.writeString(ps1, script(port), StandardCharsets.UTF_8);

        String sysRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        String command = "\"" + sysRoot + "\\System32\\conhost.exe\" --headless powershell.exe -NoProfile"
                + " -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + ps1 + "\" \"%1\"";
        String reg = """
                $k = 'HKCU:\\Software\\Classes\\claude-bridge'
                New-Item -Path $k -Force -Value 'URL:claude-bridge' | Out-Null
                New-ItemProperty -Path $k -Name 'URL Protocol' -Value '' -Force | Out-Null
                New-Item -Path "$k\\shell\\open\\command" -Force -Value '%s' | Out-Null
                """.formatted(command.replace("'", "''"));
        WindowsToaster.runPowerShell(reg);
        return ps1;
    }
}

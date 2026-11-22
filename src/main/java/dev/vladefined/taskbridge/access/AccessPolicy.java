package dev.vladefined.taskbridge.access;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the remote side may ask for. Checked before the owner is asked, and again
 * before an approved change is applied: a request that breaks these rules never
 * reaches the owner as something to approve.
 */
public final class AccessPolicy {

    public static final Pattern FOLDER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    public static final int MAX_TOOLS = 20;
    public static final int MIN_TIMEOUT_MINUTES = 1;
    public static final int MAX_TIMEOUT_MINUTES = 240;

    /**
     * {@code Name} or {@code Name(spec)}. The spec allows the characters tool rules use
     * ({@code Bash(git diff:*)}, {@code Read(src/**)}), but nothing cmd.exe would
     * interpret ({@code & | < > ^ % ! "}) and no comma: the list is joined with commas
     * into one {@code --allowedTools} argument.
     */
    private static final Pattern TOOL = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]{0,63}(\\([A-Za-z0-9_ .:*/\\\\=@+~#\\[\\]{}-]{1,160}\\))?");
    /** Tools that run commands: never allowed without a concrete command prefix. */
    private static final Set<String> SHELLS = Set.of("bash", "powershell", "shell");
    /** Tools that change files or run commands: marked ⚠️ in approval requests. */
    private static final Set<String> RISKY = Set.of("bash", "powershell", "shell", "edit", "write",
            "multiedit", "notebookedit");
    /**
     * Command prefixes that start an interpreter or a launcher: {@code Bash(python:*)} lets
     * the agent run {@code python -c "..."}, i.e. any code. Allowed, but marked loudly.
     */
    private static final Set<String> INTERPRETERS = Set.of("python", "python3", "py", "pythonw", "node", "nodejs",
            "deno", "bun", "npx", "npm", "pnpm", "yarn", "uv", "uvx", "pipx", "ruby", "perl", "php", "lua", "java",
            "jshell", "groovy", "kotlin", "scala", "dotnet", "go", "cargo", "rustc", "gcc", "make", "cmake", "mvn",
            "gradle", "gradlew", "powershell", "pwsh", "cmd", "bash", "sh", "zsh", "wsl", "env", "sudo", "xargs",
            "docker", "podman", "kubectl", "ssh", "osascript", "wscript", "cscript", "mshta", "rundll32",
            "regsvr32", "start", "invoke-expression", "iex");
    /** Path segments never granted, wherever they are. */
    private static final Set<String> SECRET_SEGMENTS = Set.of(".ssh", ".claude", ".gnupg", ".aws",
            ".azure", ".kube", ".docker");

    private AccessPolicy() {
    }

    public static String checkFolderName(String name) {
        if (name == null || !FOLDER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("folder name must be [A-Za-z0-9._-], up to 64 characters");
        }
        return name;
    }

    /** @return the tool, trimmed; throws with a reason the caller can act on. */
    public static String checkTool(String raw) {
        String t = raw == null ? "" : raw.strip();
        if (!TOOL.matcher(t).matches()) {
            throw new IllegalArgumentException("not an allowed tool rule: '" + t
                    + "' (use Name or Name(prefix), no commas, quotes or & | < > ^ % !)");
        }
        int p = t.indexOf('(');
        String name = (p < 0 ? t : t.substring(0, p)).toLowerCase(Locale.ROOT);
        if (SHELLS.contains(name)) {
            String spec = p < 0 ? "" : t.substring(p + 1, t.length() - 1);
            if (spec.replaceAll("[*:\\s]", "").isEmpty()) {
                throw new IllegalArgumentException("'" + t + "' would allow any command; give a command prefix, "
                        + "e.g. Bash(mvn test:*)");
            }
        }
        return t;
    }

    /** Checked, trimmed, without duplicates, at most {@link #MAX_TOOLS}. */
    public static List<String> checkTools(List<String> tools) {
        if (tools == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String t : tools) {
            out.add(checkTool(t));
        }
        if (out.size() > MAX_TOOLS) {
            throw new IllegalArgumentException("at most " + MAX_TOOLS + " tools");
        }
        return List.copyOf(out);
    }

    public static boolean risky(String tool) {
        int p = tool.indexOf('(');
        return RISKY.contains((p < 0 ? tool : tool.substring(0, p)).toLowerCase(Locale.ROOT));
    }

    /**
     * Does this rule let the agent start an interpreter or a build tool, i.e. run
     * arbitrary code? {@code Bash(python:*)}, {@code Bash(npm run:*)}, {@code Bash(mvn test:*)}
     * (a build runs the project's code and plugins).
     */
    public static boolean interpreter(String tool) {
        int p = tool.indexOf('(');
        if (p < 0 || !SHELLS.contains(tool.substring(0, p).toLowerCase(Locale.ROOT))) {
            return false;
        }
        String spec = tool.substring(p + 1, tool.length() - 1).strip();
        // "python:*" -> python; "python3 -m pytest:*" -> python3; "C:/Py/python.exe:*" -> C:/Py/python.exe
        String first = spec.split("\\s", 2)[0].replaceAll(":\\*?$", "").toLowerCase(Locale.ROOT);
        String exe = first.replaceAll("^.*[/\\\\]", "").replaceAll("\\.(exe|cmd|bat|ps1)$", "");
        return INTERPRETERS.contains(exe) || exe.matches("python\\d+(\\.\\d+)?");
    }

    /** Rules among {@code tools} that run interpreters (for a separate warning line). */
    public static List<String> interpreters(List<String> tools) {
        return tools == null ? List.of() : tools.stream().filter(AccessPolicy::interpreter).toList();
    }

    /** "Read, ⚠️ Edit, ‼️ Bash(python:*)": ⚠️ changes files or runs commands, ‼️ runs any code. */
    public static String describe(List<String> tools) {
        if (tools == null || tools.isEmpty()) {
            return "—";
        }
        return String.join(", ", tools.stream()
                .map(t -> interpreter(t) ? "‼️ " + t : risky(t) ? "⚠️ " + t : t).toList());
    }

    /**
     * A requested folder must be one of the roots or inside one. No roots configured:
     * no new folders at all (changing or removing existing ones still works).
     */
    public static void checkInsideRoots(Path real, List<Path> roots) {
        if (roots == null || roots.isEmpty()) {
            throw new IllegalArgumentException("new folders cannot be requested: no roots are configured "
                    + "(bridge.access.roots / BRIDGE_ACCESS_ROOTS)");
        }
        for (Path r : roots) {
            if (under(real, r)) {
                return;
            }
        }
        throw new IllegalArgumentException("path is outside the allowed roots " + roots + ": " + real);
    }

    public static int checkTimeout(Integer minutes) {
        int m = minutes == null ? 30 : minutes;
        if (m < MIN_TIMEOUT_MINUTES || m > MAX_TIMEOUT_MINUTES) {
            throw new IllegalArgumentException("timeoutMinutes must be " + MIN_TIMEOUT_MINUTES + ".."
                    + MAX_TIMEOUT_MINUTES);
        }
        return m;
    }

    /**
     * The real directory a folder request may point at.
     *
     * @param protectedDirs the bridge's own directories: neither they, nor anything inside
     *                      them, nor anything containing them can be granted
     * @return the real path (symlinks and {@code ..} resolved) — what the owner approves
     */
    public static Path checkPath(String raw, Path home, List<Path> protectedDirs) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Path p = Path.of(raw.strip());
        if (!p.isAbsolute()) {
            throw new IllegalArgumentException("path must be absolute");
        }
        Path real;
        try {
            real = p.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("path does not exist: " + raw);
        }
        if (!Files.isDirectory(real)) {
            throw new IllegalArgumentException("path is not a directory: " + real);
        }
        if (real.getParent() == null) {
            throw new IllegalArgumentException("a whole drive cannot be granted: " + real);
        }
        for (String segment : segments(real)) {
            if (SECRET_SEGMENTS.contains(segment)) {
                throw new IllegalArgumentException("paths with " + segment + " cannot be granted");
            }
        }
        for (String env : List.of("SystemRoot", "ProgramFiles", "ProgramFiles(x86)", "ProgramData")) {
            String v = System.getenv(env);
            if (v != null && !v.isBlank() && under(real, Path.of(v))) {
                throw new IllegalArgumentException("system folders cannot be granted: " + real);
            }
        }
        if (home != null) {
            if (under(home, real)) {
                throw new IllegalArgumentException("this folder contains your user profile: " + real);
            }
            if (under(real, home.resolve("AppData"))) {
                throw new IllegalArgumentException("AppData cannot be granted: " + real);
            }
        }
        for (Path d : protectedDirs) {
            if (d != null && (under(real, d) || under(d, real))) {
                throw new IllegalArgumentException("the bridge's own folders cannot be granted: " + real);
            }
        }
        return real;
    }

    /**
     * Claude Code also applies the folder's own permission settings on top of the
     * bridge's tool list: tell the owner if there are any.
     */
    public static List<String> settingsWarnings(Path dir, ObjectMapper json) {
        List<String> out = new ArrayList<>();
        for (String f : List.of("settings.json", "settings.local.json")) {
            Path s = dir.resolve(".claude").resolve(f);
            if (!Files.isRegularFile(s)) {
                continue;
            }
            try {
                JsonNode allow = json.readTree(s.toFile()).path("permissions").path("allow");
                if (allow.isArray() && !allow.isEmpty()) {
                    List<String> rules = new ArrayList<>();
                    allow.forEach(a -> rules.add(a.asText()));
                    out.add(".claude/" + f + " allows: " + String.join(", ", rules));
                }
            } catch (IOException e) {
                out.add(".claude/" + f + " exists but could not be read");
            }
        }
        return out;
    }

    /** Is {@code p} the same as {@code dir} or inside it (case-insensitive, as on Windows)? */
    static boolean under(Path p, Path dir) {
        String a = norm(p);
        String b = norm(dir);
        return a.equals(b) || a.startsWith(b.endsWith(sep()) ? b : b + sep());
    }

    /**
     * Real path of {@code p}, or of its nearest existing parent plus the rest: so 8.3 short
     * names (C:\Users\VLADEF~1) and links compare equal to their long forms.
     */
    private static String norm(Path p) {
        Path n = p.toAbsolutePath().normalize();
        Path existing = n;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing != null) {
            try {
                n = existing.toRealPath().resolve(existing.relativize(n));
            } catch (IOException ignored) {
                // keep the normalized form
            }
        }
        return n.toString().toLowerCase(Locale.ROOT);
    }

    private static String sep() {
        return java.io.File.separator;
    }

    private static List<String> segments(Path p) {
        List<String> out = new ArrayList<>();
        for (Path s : p) {
            out.add(s.toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}

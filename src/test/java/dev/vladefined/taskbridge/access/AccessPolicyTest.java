package dev.vladefined.taskbridge.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class AccessPolicyTest {

    @Test
    void ordinaryToolRulesPass() {
        assertThat(AccessPolicy.checkTools(List.of(" Read ", "Grep", "Bash(mvn test:*)", "Bash(git diff:*)",
                "Read(src/**)", "Read")))
                .containsExactly("Read", "Grep", "Bash(mvn test:*)", "Bash(git diff:*)", "Read(src/**)");
    }

    @Test
    void anyCommandIsNeverGranted() {
        for (String t : List.of("Bash", "bash", "Bash(*)", "Bash(:*)", "Bash( * )", "PowerShell", "PowerShell(*)")) {
            assertThatThrownBy(() -> AccessPolicy.checkTool(t)).as(t)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void nothingThatCouldBreakTheCommandLine() {
        for (String t : List.of("Read,Write", "Bash(a & b)", "Bash(a | b)", "Bash(x\"y)", "Bash(%PATH%)",
                "Bash(a > f)", "Bash(a^b)", "Bash(rm -rf /) && x", "--dangerously-skip-permissions", "", "Read(")) {
            assertThatThrownBy(() -> AccessPolicy.checkTool(t)).as(t)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void riskyToolsAreMarked() {
        assertThat(AccessPolicy.describe(List.of("Read", "Edit", "Bash(git diff:*)", "Bash(mvn test:*)")))
                .isEqualTo("Read, ⚠️ Edit, ⚠️ Bash(git diff:*), ‼️ Bash(mvn test:*)");
    }

    @Test
    void interpretersAreRecognised() {
        for (String t : List.of("Bash(python:*)", "Bash(python3 -m pytest:*)", "Bash(python3.12:*)", "Bash(node:*)",
                "Bash(npx tsc:*)", "Bash(powershell -File:*)", "Bash(pwsh:*)", "Bash(cmd /c:*)", "Bash(C:/Python/python.exe:*)",
                "Bash(uv run:*)", "Bash(env:*)", "PowerShell(python:*)")) {
            assertThat(AccessPolicy.interpreter(t)).as(t).isTrue();
        }
        for (String t : List.of("Read", "Edit", "Bash(git status:*)", "Bash(git diff:*)", "Bash(ls:*)", "Read(python/**)")) {
            assertThat(AccessPolicy.interpreter(t)).as(t).isFalse();
        }
        assertThat(AccessPolicy.interpreters(List.of("Read", "Bash(python:*)"))).containsExactly("Bash(python:*)");
    }

    @Test
    void rootsLimitWhereFoldersMayBe(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("code"));
        Path inside = Files.createDirectories(root.resolve("app"));
        Path outside = Files.createDirectories(tmp.resolve("other"));
        AccessPolicy.checkInsideRoots(inside.toRealPath(), List.of(root));
        AccessPolicy.checkInsideRoots(root.toRealPath(), List.of(root));
        assertThatThrownBy(() -> AccessPolicy.checkInsideRoots(outside.toRealPath(), List.of()))
                .hasMessageContaining("no roots");                                // no roots: no new folders
        assertThatThrownBy(() -> AccessPolicy.checkInsideRoots(outside.toRealPath(), List.of(root)))
                .hasMessageContaining("outside the allowed roots");
        // a sibling with the same prefix is not inside
        Path sibling = Files.createDirectories(tmp.resolve("code-old"));
        assertThatThrownBy(() -> AccessPolicy.checkInsideRoots(sibling.toRealPath(), List.of(root)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aProjectFolderPasses(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path proj = Files.createDirectories(tmp.resolve("work").resolve("proj"));
        assertThat(AccessPolicy.checkPath(proj.toString(), home, List.of()))
                .isEqualTo(proj.toRealPath());
        // .. is resolved before anything is checked or shown
        assertThat(AccessPolicy.checkPath(proj.resolve("..").resolve("proj").toString(), home, List.of()))
                .isEqualTo(proj.toRealPath());
    }

    @Test
    void dangerousPathsAreRefused(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path appData = Files.createDirectories(home.resolve("AppData").resolve("Local").resolve("x"));
        Path ssh = Files.createDirectories(tmp.resolve("keys").resolve(".ssh"));
        Path bridge = Files.createDirectories(tmp.resolve("bridge"));
        Path insideBridge = Files.createDirectories(bridge.resolve("outputs"));
        Path file = Files.writeString(tmp.resolve("f.txt"), "x");
        List<Path> prot = List.of(bridge);
        for (String p : List.of(tmp.getRoot().toString(), home.toString(), tmp.toString(), appData.toString(),
                ssh.toString(), bridge.toString(), insideBridge.toString(), file.toString(),
                tmp.resolve("missing").toString(), "relative\\path", "")) {
            assertThatThrownBy(() -> AccessPolicy.checkPath(p, home, prot)).as(p)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        String windows = System.getenv("SystemRoot");
        if (windows != null) {
            assertThatThrownBy(() -> AccessPolicy.checkPath(windows, home, prot))
                    .hasMessageContaining("system folders");
        }
    }

    @Test
    void folderSettingsThatAddPermissionsAreReported(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".claude"));
        Files.writeString(tmp.resolve(".claude").resolve("settings.json"),
                "{\"permissions\":{\"allow\":[\"Bash(npm:*)\"]}}");
        assertThat(AccessPolicy.settingsWarnings(tmp, new ObjectMapper()))
                .containsExactly(".claude/settings.json allows: Bash(npm:*)");
    }

    @Test
    void timeoutAndNameRules() {
        assertThat(AccessPolicy.checkTimeout(null)).isEqualTo(30);
        assertThatThrownBy(() -> AccessPolicy.checkTimeout(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessPolicy.checkTimeout(1000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessPolicy.checkFolderName("../x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AccessPolicy.checkFolderName("my-repo.v2")).isEqualTo("my-repo.v2");
    }
}

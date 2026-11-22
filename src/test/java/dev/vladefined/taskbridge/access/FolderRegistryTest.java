package dev.vladefined.taskbridge.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.vladefined.taskbridge.config.BridgeProperties;
import dev.vladefined.taskbridge.config.BridgeProperties.Folder;

class FolderRegistryTest {

    static BridgeProperties props() {
        return new BridgeProperties("0123456789abcdef0123456789abcdef", Duration.ofMinutes(5),
                Duration.ofMinutes(30), 3000, 3500, null, null, null,
                Map.of("pibench", new Folder("E:\\pibench", List.of("Read"), null)));
    }

    @Test
    void approvedChangesSurviveARestartAndOverrideTheConfig(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("state").resolve("folders.json");
        ObjectMapper json = new ObjectMapper();
        FolderRegistry r = new FolderRegistry(props(), json, file.toString());
        assertThat(r.list()).extracting(FolderRegistry.Entry::name).containsExactly("pibench");

        r.put("proj", new Folder("D:\\proj", List.of("Read", "Bash(mvn test:*)"), Duration.ofMinutes(20)));
        r.put("pibench", new Folder("E:\\pibench", List.of("Read", "Edit"), null));
        FolderRegistry again = new FolderRegistry(props(), json, file.toString());
        assertThat(again.get("proj").orElseThrow().allowedTools()).containsExactly("Read", "Bash(mvn test:*)");
        assertThat(again.get("proj").orElseThrow().timeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(again.get("pibench").orElseThrow().allowedTools()).containsExactly("Read", "Edit");
        assertThat(again.list()).extracting(FolderRegistry.Entry::source).containsExactly("request", "request");

        again.remove("pibench");
        again.remove("proj");
        FolderRegistry third = new FolderRegistry(props(), json, file.toString());
        assertThat(third.get("pibench")).isEmpty();      // a configured folder stays hidden
        assertThat(third.list()).isEmpty();
        third.put("pibench", new Folder("E:\\pibench", List.of("Read"), null));
        assertThat(new FolderRegistry(props(), json, file.toString()).get("pibench")).isPresent();
    }
}

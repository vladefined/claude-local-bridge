package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.vladefined.taskbridge.core.ApprovalChannel;
import dev.vladefined.taskbridge.core.HmacVerifier;
import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.TaskService;
import dev.vladefined.taskbridge.core.TaskStatus;
import dev.vladefined.taskbridge.runner.ClaudeRunner;
import dev.vladefined.taskbridge.runner.RunResult;

@SpringBootTest
@AutoConfigureMockMvc
class BridgeFlowTest {

    @TempDir
    static Path tmp;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + tmp.resolve("t.db"));
        r.add("bridge.hmac-secret", () -> "0123456789abcdef0123456789abcdef");
        r.add("bridge.telegram.enabled", () -> "false");
        r.add("bridge.desktop.enabled", () -> "false");
        r.add("bridge.folders.demo.path", () -> tmp.toString());
    }

    static class RecordingChannel implements ApprovalChannel {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void requestApproval(Task t) {
            events.add("request:" + t.id());
        }

        @Override
        public void onDecided(Task t) {
            events.add("decided:" + t.id() + ":" + t.status() + ":" + t.decidedBy());
        }

        @Override
        public void onFinished(Task t) {
            events.add("finished:" + t.id() + ":" + t.status());
        }
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        RecordingChannel recordingChannel() {
            return new RecordingChannel();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired HmacVerifier hmac;
    @Autowired TaskService tasks;
    @Autowired RecordingChannel channel;
    @Autowired ObjectMapper json;
    @MockitoBean ClaudeRunner runner;

    MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder b, String method, String path, byte[] body) {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String sig = HexFormat.of().formatHex(hmac.sign(ts, nonce, method, path, body));
        return b.header("X-Bridge-Timestamp", ts).header("X-Bridge-Nonce", nonce)
                .header("X-Bridge-Signature", sig).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String submit(String text) throws Exception {
        byte[] body = ("{\"folder\":\"demo\",\"text\":" + json.writeValueAsString(text)
                + ",\"mode\":\"continue\",\"sender\":\"test\"}").getBytes(StandardCharsets.UTF_8);
        String resp = mvc.perform(signed(post("/tasks"), "POST", "/tasks", body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).path("id").asText();
    }

    @Test
    void unsignedRequestIsRefused() throws Exception {
        mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownFolderIsRefused() throws Exception {
        byte[] body = "{\"folder\":\"../etc\",\"text\":\"hi\",\"mode\":\"NEW\"}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(signed(post("/tasks"), "POST", "/tasks", body)).andExpect(status().isBadRequest());
    }

    @Test
    void unknownFieldsAreRefused() throws Exception {
        // e.g. an attempt to pass a path or extra flags the API does not offer
        byte[] body = "{\"folder\":\"demo\",\"text\":\"hi\",\"mode\":\"NEW\",\"path\":\"C:\\\\\"}"
                .getBytes(StandardCharsets.UTF_8);
        mvc.perform(signed(post("/tasks"), "POST", "/tasks", body)).andExpect(status().isBadRequest());
    }

    @Test
    void approvedTaskRunsOnceAndResultIsReadable() throws Exception {
        when(runner.run(any(), any())).thenReturn(new RunResult(true, "all good", "sess-1", null));
        String id = submit("Run the benchmark");
        assertThat(channel.events).contains("request:" + id);

        assertThat(tasks.decide(id, true, "desktop")).isTrue();
        assertThat(tasks.decide(id, false, "telegram")).isFalse();   // second channel loses

        verify(runner, timeout(5000).times(1)).run(any(), any());
        Thread.sleep(200);
        mvc.perform(signed(get("/tasks/" + id), "GET", "/tasks/" + id, new byte[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.decidedBy").value("desktop"))
                .andExpect(jsonPath("$.result").value("all good"));
        assertThat(channel.events).contains("decided:" + id + ":APPROVED:desktop", "finished:" + id + ":DONE");
    }

    @Test
    void simultaneousPressesProduceExactlyOneDecision() throws Exception {
        when(runner.run(any(), any())).thenReturn(new RunResult(true, "ok", null, null));
        String id = submit("race");
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            boolean approve = i % 2 == 0;
            String ch = i % 2 == 0 ? "desktop" : "telegram";
            results.add(pool.submit(() -> {
                go.await();
                return tasks.decide(id, approve, ch);
            }));
        }
        go.countDown();
        long winners = 0;
        for (Future<Boolean> f : results) {
            winners += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
        assertThat(channel.events.stream().filter(e -> e.startsWith("decided:" + id))).hasSize(1);
    }

    @Test
    void longResultIsCutButFullOutputStaysAvailable() throws Exception {
        String full = "line\n".repeat(2000);                 // 10 000 chars > max-result-chars 3500
        Path file = tmp.resolve("full-output.txt");
        java.nio.file.Files.writeString(file, full);
        when(runner.run(any(), any())).thenReturn(new RunResult(true, full, null, file));
        String id = submit("produce a lot");
        tasks.decide(id, true, "telegram");
        verify(runner, timeout(5000).times(1)).run(any(), any());
        Thread.sleep(200);

        Task t = tasks.find(id).orElseThrow();
        assertThat(t.resultTruncated()).isTrue();
        assertThat(t.result()).hasSize(3500);
        assertThat(t.outputPath()).isEqualTo(file.toString());
        mvc.perform(signed(get("/tasks/" + id + "/output"), "GET", "/tasks/" + id + "/output", new byte[0]))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo(full));
    }

    @Test
    void rejectedTaskNeverRuns() throws Exception {
        String id = submit("do not run me");
        assertThat(tasks.decide(id, false, "telegram")).isTrue();
        assertThat(tasks.decide(id, true, "desktop")).isFalse();
        Thread.sleep(300);
        verify(runner, times(0)).run(any(), any());
        assertThat(tasks.find(id).orElseThrow().status()).isEqualTo(TaskStatus.REJECTED);
    }
}

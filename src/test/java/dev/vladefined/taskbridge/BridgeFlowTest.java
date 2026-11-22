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
import org.springframework.test.annotation.DirtiesContext;
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
// Close the Spring context (and its SQLite connection) before JUnit deletes
// the @TempDir; on Windows an open t.db cannot be deleted.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

        @Override
        public void requestMessageApproval(Task t, dev.vladefined.taskbridge.core.TaskMessage m,
                                           List<String> since, List<String> recent) {
            events.add("msg-request:" + m.id() + ":" + since.size() + ":" + recent.size());
        }

        @Override
        public void onMessageUpdated(Task t, dev.vladefined.taskbridge.core.TaskMessage m) {
            events.add("msg:" + m.id() + ":" + m.status());
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
        when(runner.run(any(), any())).thenReturn(
                new RunResult(true, "all good\n\nSUMMARY: ran it, all green", "sess-1", null));
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
                .andExpect(jsonPath("$.result").value("all good"))
                .andExpect(jsonPath("$.summary").value("ran it, all green"));
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
    void progressAndLogAreAvailableToTheCaller() throws Exception {
        when(runner.run(any(), any())).thenAnswer(inv -> {
            Task t = inv.getArgument(0);
            Path dir = java.nio.file.Files.createDirectories(tmp.resolve("out-" + t.id()));
            Path full = dir.resolve(t.id() + ".txt");
            java.nio.file.Files.writeString(full, "done");
            java.nio.file.Files.writeString(dir.resolve(t.id() + ClaudeRunner.STDOUT_SUFFIX), StreamLogTest.STREAM,
                    StandardCharsets.UTF_8);
            java.nio.file.Files.writeString(dir.resolve(t.id() + ClaudeRunner.LOG_SUFFIX), "the log",
                    StandardCharsets.UTF_8);
            return new RunResult(true, "done", "s-1", full);
        });
        String id = submit("read run.py");
        // before approval: the status says so, no steps yet
        mvc.perform(signed(get("/tasks/" + id + "/progress"), "GET", "/tasks/" + id + "/progress", new byte[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.steps").isEmpty());
        tasks.decide(id, true, "telegram");
        verify(runner, timeout(5000).times(1)).run(any(), any());
        Thread.sleep(200);
        mvc.perform(signed(get("/tasks/" + id + "/progress"), "GET", "/tasks/" + id + "/progress", new byte[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.decidedBy").value("telegram"))
                .andExpect(jsonPath("$.steps[1]").value("→ Read(E:\\pibench\\run.py)"));
        mvc.perform(signed(get("/tasks/" + id + "/log"), "GET", "/tasks/" + id + "/log", new byte[0]))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEqualTo("the log"));
        // the signature covers the path: a /progress signature does not open /log
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String sig = HexFormat.of().formatHex(hmac.sign(ts, "n-1", "GET", "/tasks/" + id + "/progress", new byte[0]));
        mvc.perform(get("/tasks/" + id + "/log").header("X-Bridge-Timestamp", ts).header("X-Bridge-Nonce", "n-1")
                .header("X-Bridge-Signature", sig)).andExpect(status().isUnauthorized());
    }

    MockHttpServletRequestBuilder message(String id, String body) {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return signed(post("/tasks/" + id + "/messages"), "POST", "/tasks/" + id + "/messages", b);
    }

    @Test
    void messageToARunningTaskNeedsAFreshCursorAndTheOwnersApproval() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Path stream = tmp.resolve("live.stdout.json");
        // the agent has made 4 steps and is still working (no result event yet)
        java.nio.file.Files.writeString(stream, StreamLogTest.STREAM.substring(0,
                StreamLogTest.STREAM.indexOf("{\"type\":\"result\"")), StandardCharsets.UTF_8);
        when(runner.runningStdout(any())).thenReturn(stream);
        when(runner.isLive(any())).thenReturn(true);
        // like the real runner: delivered at the current end of the stream
        when(runner.deliver(any(), any())).thenAnswer(inv -> java.nio.file.Files.size(stream));
        when(runner.run(any(), any())).thenAnswer(inv -> {
            release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            return new RunResult(true, "ok", "s-1", null);
        });
        try {
            String id = submit("long job");
            // before it runs: refused, the caller is told to send a new task
            mvc.perform(message(id, "{\"text\":\"x\",\"cursor\":0}")).andExpect(status().isConflict());
            tasks.decide(id, true, "telegram");
            long until = System.currentTimeMillis() + 5000;
            while (tasks.find(id).orElseThrow().status() != TaskStatus.RUNNING) {
                assertThat(System.currentTimeMillis()).isLessThan(until);
                Thread.sleep(20);
            }
            String progress = mvc.perform(signed(get("/tasks/" + id + "/progress"), "GET",
                            "/tasks/" + id + "/progress", new byte[0]))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long cursor = json.readTree(progress).path("cursor").asLong();
            assertThat(cursor).isEqualTo(java.nio.file.Files.size(stream));

            // no cursor: the caller has not looked at what the agent is doing
            mvc.perform(message(id, "{\"text\":\"also run lint\"}")).andExpect(status().isBadRequest());
            // cursor from the start: 4 steps behind, more than 3
            mvc.perform(message(id, "{\"text\":\"also run lint\",\"cursor\":0}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.stepsBehind").value(4));
            // invisible characters are refused like in tasks
            mvc.perform(message(id, "{\"text\":\"a\\u202Eb\",\"cursor\":" + cursor + "}"))
                    .andExpect(status().isBadRequest());

            String resp = mvc.perform(message(id, "{\"text\":\"also run lint\",\"cursor\":" + cursor
                            + ",\"sender\":\"cloud\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andReturn().getResponse().getContentAsString();
            String mid = json.readTree(resp).path("id").asText();
            assertThat(channel.events).contains("msg-request:" + mid + ":0:3");
            verify(runner, times(0)).deliver(any(), any());     // nothing before approval

            assertThat(tasks.decideMessage(mid, true, "telegram")).isTrue();
            assertThat(tasks.decideMessage(mid, false, "desktop")).isFalse();
            verify(runner).deliver(id, "also run lint");
            assertThat(channel.events).contains("msg:" + mid + ":DELIVERED");
            mvc.perform(signed(get("/tasks/" + id + "/progress"), "GET", "/tasks/" + id + "/progress", new byte[0]))
                    .andExpect(jsonPath("$.messages[0].status").value("DELIVERED"))
                    .andExpect(jsonPath("$.steps[-1]").value("✉ Message from the caller (approved): also run lint"));

            // a second message stays pending; when the task ends it is reported as not delivered
            String resp2 = mvc.perform(message(id, "{\"text\":\"and docs\",\"cursor\":" + cursor + "}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            String mid2 = json.readTree(resp2).path("id").asText();
            release.countDown();
            until = System.currentTimeMillis() + 5000;
            while (tasks.find(id).orElseThrow().status() == TaskStatus.RUNNING) {
                assertThat(System.currentTimeMillis()).isLessThan(until);
                Thread.sleep(20);
            }
            assertThat(channel.events).contains("msg:" + mid2 + ":UNDELIVERED");
            mvc.perform(signed(get("/tasks/" + id), "GET", "/tasks/" + id, new byte[0]))
                    .andExpect(jsonPath("$.messages[1].status").value("UNDELIVERED"));
        } finally {
            release.countDown();
        }
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

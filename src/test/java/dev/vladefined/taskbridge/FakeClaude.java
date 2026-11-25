package dev.vladefined.taskbridge;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A stand-in for {@code claude -p --input-format stream-json --output-format stream-json}
 * that runs on any OS (it is started with the test JVM's own java).
 *
 * <p>Usage: {@code FakeClaude <steps> <stepMillis> [<backgroundMillis> <lingerMillis>] [claude flags ignored]}.
 * With {@code backgroundMillis > 0} a command is "moved to the background" after the
 * steps and the stream stays silent that long before it completes, using the events
 * Claude Code writes for that. With {@code lingerMillis > 0} the process stays alive that
 * long after stdin is closed, like a process that does not exit after answering. Reads the task
 * as the first JSON line on stdin, makes {@code steps} "tool calls" with a pause each,
 * and between steps picks up any further user messages, like Claude Code does. Answers
 * with a result event, then keeps reading: every later message is a new turn with its
 * own result. Exits when stdin is closed.
 */
public final class FakeClaude {

    static final ObjectMapper JSON = new ObjectMapper();
    static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    static final Object EOF = new Object();

    public static void main(String[] args) throws Exception {
        int steps = Integer.parseInt(args[0]);
        long stepMillis = Long.parseLong(args[1]);
        long backgroundMillis = args.length > 3 && args[2].matches("\\d+") ? Long.parseLong(args[2]) : 0;
        long lingerMillis = args.length > 3 && args[3].matches("\\d+") ? Long.parseLong(args[3]) : 0;
        LinkedBlockingQueue<Object> in = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (!line.isBlank()) {
                        in.put(JSON.readTree(line).path("message").path("content").asText());
                    }
                }
            } catch (Exception ignored) {
                // stdin gone
            } finally {
                in.add(EOF);
            }
        });
        reader.setDaemon(true);
        reader.start();

        Object first = in.take();
        if (first == EOF) {
            return;
        }
        emit(obj("system").put("subtype", "init").put("session_id", "fake-1"));
        List<String> seen = new ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            ObjectNode a = obj("assistant");
            var c = a.putObject("message").putArray("content").addObject();
            c.put("type", "tool_use").put("id", "t" + i).put("name", "Bash");
            c.putObject("input").put("command", "step " + i);
            emit(a);
            Thread.sleep(stepMillis);
            ObjectNode u = obj("user");
            u.putObject("message").putArray("content").addObject()
                    .put("type", "tool_result").put("tool_use_id", "t" + i).put("content", "ok " + i);
            emit(u);
            for (Object m; (m = in.poll()) != null; ) {
                if (m == EOF) {
                    in.add(EOF);
                    break;
                }
                seen.add((String) m);
                text("got: " + m);
            }
        }
        if (backgroundMillis > 0) {
            emit(obj("system").put("subtype", "task_updated").put("task_id", "bg1")
                    .set("patch", JSON.createObjectNode().put("is_backgrounded", true)));
            ObjectNode changed = obj("system").put("subtype", "background_tasks_changed");
            changed.putArray("tasks").addObject().put("task_id", "bg1").put("description", "long job");
            emit(changed);
            Thread.sleep(backgroundMillis);
            emit(obj("system").put("subtype", "task_notification").put("task_id", "bg1")
                    .put("status", "completed").put("summary", "Background command \"long job\" completed (exit code 0)"));
            ObjectNode none = obj("system").put("subtype", "background_tasks_changed");
            none.putArray("tasks");
            emit(none);
        }
        String answer = "done: " + first + (seen.isEmpty() ? "" : " | with: " + String.join(", ", seen));
        text(answer);
        emit(obj("result").put("subtype", "success").put("is_error", false).put("result", answer)
                .put("session_id", "fake-1"));
        // later messages: one more turn each, until stdin is closed
        for (Object m; (m = in.poll(30, TimeUnit.SECONDS)) != null && m != EOF; ) {
            String late = "late: " + m;
            text(late);
            emit(obj("result").put("subtype", "success").put("is_error", false).put("result", late)
                    .put("session_id", "fake-1"));
        }
        Thread.sleep(lingerMillis);
    }

    static ObjectNode obj(String type) {
        return JSON.createObjectNode().put("type", type);
    }

    static void text(String s) {
        ObjectNode a = obj("assistant");
        a.putObject("message").putArray("content").addObject().put("type", "text").put("text", s);
        emit(a);
    }

    static void emit(JsonNode n) {
        OUT.print(n.toString() + "\n");
        OUT.flush();
    }

    /** The command that starts this class with the current JVM. */
    static List<String> command(int steps, long stepMillis) {
        return command(steps, stepMillis, 0, 0);
    }

    static List<String> command(int steps, long stepMillis, long backgroundMillis, long lingerMillis) {
        String java = ProcessHandle.current().info().command().orElse("java");
        return List.of(java, "-cp", System.getProperty("java.class.path"), FakeClaude.class.getName(),
                String.valueOf(steps), String.valueOf(stepMillis),
                String.valueOf(backgroundMillis), String.valueOf(lingerMillis));
    }
}

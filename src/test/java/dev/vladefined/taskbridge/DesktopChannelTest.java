package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.vladefined.taskbridge.core.RunMode;
import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.TaskStatus;
import dev.vladefined.taskbridge.desktop.ToastXml;

class DesktopChannelTest {

    static Task task(String text) {
        return new Task("abc", "pibench", RunMode.CONTINUE, null, text, "cloud <thread>", "a".repeat(64),
                TaskStatus.PENDING, null, Instant.now(), null, null, "tok", null, null, null, false, null);
    }

    @Test
    void toastEscapesTaskText() {
        String xml = ToastXml.build(task("</text><action content=\"x\" arguments=\"evil\"/>"),
                "http://127.0.0.1:8766/t/tok", 300);
        assertThat(xml).doesNotContain("arguments=\"evil\"").contains("&lt;/text&gt;");
        assertThat(xml).contains("http://127.0.0.1:8766/t/tok/approve");
    }

    @Test
    void truncatedResultMessageFitsTelegramAndSaysSo() {
        Task t = new Task("abc", "pibench", RunMode.CONTINUE, null, "x", "s", "a".repeat(64),
                TaskStatus.DONE, "telegram", Instant.now(), null, 1L, "tok", "y".repeat(3500), "sess",
                Instant.now(), true, "/out/abc.txt");
        String msg = dev.vladefined.taskbridge.telegram.TelegramFormat.result(t);
        assertThat(msg.length()).isLessThanOrEqualTo(4096);
        assertThat(msg).contains("полный вывод во вложении");
    }

    @Test
    void longTextCannotBeApprovedFromTheToast() {
        String xml = ToastXml.build(task("x".repeat(500)), "http://127.0.0.1:8766/t/tok", 300);
        assertThat(xml).doesNotContain("/approve").contains("Открыть полностью").contains("/reject");
    }
}

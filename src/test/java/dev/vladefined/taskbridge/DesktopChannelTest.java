package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import dev.vladefined.taskbridge.core.RunMode;
import dev.vladefined.taskbridge.core.Task;
import dev.vladefined.taskbridge.core.TaskStatus;
import dev.vladefined.taskbridge.core.UiText;
import dev.vladefined.taskbridge.desktop.ToastXml;

class DesktopChannelTest {

    static final UiText EN = UiText.of("en");
    static final UiText RU = UiText.of("ru");

    static Task task(String text) {
        return new Task("abc", "pibench", RunMode.CONTINUE, null, text, "cloud <thread>", "a".repeat(64),
                TaskStatus.PENDING, null, Instant.now(), null, null, "tok", null, null, null, false, null, null);
    }

    @Test
    void toastEscapesTaskText() {
        String xml = ToastXml.build(EN, task("</text><action content=\"x\" arguments=\"evil\"/>"),
                "http://127.0.0.1:8766/t/tok", false, 300);
        assertThat(xml).doesNotContain("arguments=\"evil\"").contains("&lt;/text&gt;");
        assertThat(xml).contains("http://127.0.0.1:8766/t/tok/approve");
    }

    @Test
    void truncatedResultMessageFitsTelegramAndSaysSo() {
        Task t = new Task("abc", "pibench", RunMode.CONTINUE, null, "x", "s", "a".repeat(64),
                TaskStatus.DONE, "telegram", Instant.now(), null, 1L, "tok", "y".repeat(3500), "sess",
                Instant.now(), true, "/out/abc.txt", null);
        String msg = dev.vladefined.taskbridge.telegram.TelegramFormat.result(EN, t);
        assertThat(msg.length()).isLessThanOrEqualTo(4096);
        assertThat(msg).contains("the full output is attached");
        String ru = dev.vladefined.taskbridge.telegram.TelegramFormat.result(RU, t);
        assertThat(ru.length()).isLessThanOrEqualTo(4096);
        assertThat(ru).contains("полный вывод во вложении");
    }

    @Test
    void protocolButtonsDecideWithoutTheBrowser() {
        String xml = ToastXml.build(EN, task("hi"), "http://127.0.0.1:8766/t/tok", true, 300);
        assertThat(xml).contains("arguments=\"claude-bridge:approve/tok\"")
                .contains("arguments=\"claude-bridge:reject/tok\"")
                .doesNotContain("/t/tok/approve");
    }

    @Test
    void resultToastShowsTheSummaryEscaped() {
        Task t = new Task("abc", "pibench", RunMode.NEW, null, "x", "s", "a".repeat(64),
                TaskStatus.DONE, "desktop", Instant.now(), null, null, "tok", "long answer", "sess",
                Instant.now(), false, null, "Fixed <b>2</b> tests");
        assertThat(ToastXml.finished(RU, t)).contains("Готово: abc").contains("Fixed &lt;b&gt;2&lt;/b&gt; tests")
                .doesNotContain("long answer").doesNotContain("launch=");
    }

    @Test
    void handlerScriptOnlyAcceptsOurShape() {
        String ps = dev.vladefined.taskbridge.desktop.ProtocolHandlerAccess.script(8766);
        assertThat(ps).contains("-cnotmatch '^claude-bridge:/*(approve|reject)/([A-Za-z0-9_-]{43})/?$'")
                .contains("http://127.0.0.1:8766/t/");
    }

    @Test
    void longTextCannotBeApprovedFromTheToast() {
        String xml = ToastXml.build(EN, task("x".repeat(500)), "http://127.0.0.1:8766/t/tok", false, 300);
        assertThat(xml).doesNotContain("/approve").contains("Open in full").contains("/reject");
    }
}

package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import dev.vladefined.taskbridge.core.NewTask;
import dev.vladefined.taskbridge.core.RunMode;
import dev.vladefined.taskbridge.core.TaskValidator;

class TaskValidatorTest {

    static NewTask t(String text, RunMode mode, String session) {
        return new NewTask("pibench", text, mode, session, "cloud");
    }

    @Test
    void plainMultilineTextIsFine() {
        assertThatCode(() -> TaskValidator.validate(t("Run tests\n\tthen report", RunMode.CONTINUE, null), 3000))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesBidiOverride() {
        // U+202E makes the rest of the line render reversed in Telegram.
        assertThatThrownBy(() -> TaskValidator.validate(t("rm ‮txt.exe", RunMode.NEW, null), 3000))
                .hasMessageContaining("U+202E");
    }

    @Test
    void refusesZeroWidthCharacters() {
        assertThatThrownBy(() -> TaskValidator.validate(t("ok​hidden", RunMode.NEW, null), 3000))
                .hasMessageContaining("U+200B");
    }

    @Test
    void refusesTooLongText() {
        assertThatThrownBy(() -> TaskValidator.validate(t("x".repeat(3001), RunMode.NEW, null), 3000))
                .hasMessageContaining("longer");
    }

    @Test
    void sessionIdCannotBeAnOption() {
        assertThatThrownBy(() -> TaskValidator.validate(
                t("hi", RunMode.RESUME, "--dangerously-skip-permissions"), 3000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> TaskValidator.validate(
                t("hi", RunMode.RESUME, "3f2c9a1e-1b2c-4d5e-8f90-1234567890ab"), 3000))
                .doesNotThrowAnyException();
    }

    @Test
    void sessionIdOnlyWithResume() {
        assertThatThrownBy(() -> TaskValidator.validate(t("hi", RunMode.CONTINUE, "abc"), 3000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void senderIsCleanedAndCapped() {
        assertThat(TaskValidator.cleanSender("thread‮\n" + "x".repeat(200))).hasSize(80).doesNotContain("\n");
        assertThat(TaskValidator.cleanSender(null)).isEqualTo("unknown");
    }
}

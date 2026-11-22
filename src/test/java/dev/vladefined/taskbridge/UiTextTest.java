package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import dev.vladefined.taskbridge.core.UiText;

class UiTextTest {

    @Test
    void everyLanguageHasTheSameKeys() {
        var en = UiText.of("en").raw().stringPropertyNames();
        var ru = UiText.of("ru").raw().stringPropertyNames();
        assertThat(ru).containsExactlyInAnyOrderElementsOf(en);
    }

    @Test
    void defaultsToEnglishAndRejectsUnknownOrOddCodes() {
        assertThat(UiText.of(null).language()).isEqualTo("en");
        assertThat(UiText.of(" RU ").language()).isEqualTo("ru");
        assertThatThrownBy(() -> UiText.of("de")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> UiText.of("../x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void argumentsAreInsertedOnceAndLiterally() {
        UiText ui = UiText.of("en");
        assertThat(ui.t("file.too-big", "Run {1} $1 \\", "C:\\out")).isEqualTo(
                "Run {1} $1 \\ is larger than 50 MB and stays on the PC: C:\\out");
        assertThatThrownBy(() -> ui.t("no.such.key")).isInstanceOf(IllegalArgumentException.class);
    }
}

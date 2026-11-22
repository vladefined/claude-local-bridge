package dev.vladefined.taskbridge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.vladefined.taskbridge.core.Summary;

class SummaryTest {

    @Test
    void lastSummaryLineIsSplitOff() {
        var s = Summary.split("Did things.\nMore.\n\nSUMMARY: fixed 2 tests, all pass\n\n");
        assertThat(s.summary()).isEqualTo("fixed 2 tests, all pass");
        assertThat(s.body()).isEqualTo("Did things.\nMore.");
    }

    @Test
    void markdownAndRussianVariantsWork() {
        assertThat(Summary.split("x\n**Summary:** done").summary()).isEqualTo("done");
        assertThat(Summary.split("x\nИтог: посчитал до трёх").summary()).isEqualTo("посчитал до трёх");
    }

    @Test
    void summaryInTheMiddleIsNotTaken() {
        var s = Summary.split("SUMMARY: early\nthen more work");
        assertThat(s.summary()).isNull();
        assertThat(s.body()).isEqualTo("SUMMARY: early\nthen more work");
    }

    @Test
    void onlyLineIsBothSummaryAndEmptyBody() {
        var s = Summary.split("SUMMARY: 1, 2, 3");
        assertThat(s.summary()).isEqualTo("1, 2, 3");
        assertThat(s.body()).isEmpty();
    }

    @Test
    void longSummaryIsCut() {
        assertThat(Summary.split("SUMMARY: " + "a".repeat(1000)).summary()).hasSize(Summary.MAX_CHARS + 1);
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(Summary.split(null).summary()).isNull();
        assertThat(Summary.split("  ").summary()).isNull();
    }
}

package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-C31: the empty-cause classification matrix. Each funnel stage-zero names
 * its cause; the monotone non-increasing invariant is enforced (violation →
 * UNEXPECTED, never a guessed label).
 */
class SearchEmptyDiagnosticsTest {

    @Test
    @DisplayName("zero chunks in scope → SCOPE_EMPTY (the T-C07 scope stage)")
    void scopeEmpty() {
        assertThat(new SearchEmptyDiagnostics(0, 0, 0, 0).cause())
                .isEqualTo(SearchEmptyCause.SCOPE_EMPTY);
    }

    @Test
    @DisplayName("chunks in scope but none embedded → NOT_EMBEDDED")
    void notEmbedded() {
        assertThat(new SearchEmptyDiagnostics(12, 0, 0, 0).cause())
                .isEqualTo(SearchEmptyCause.NOT_EMBEDDED);
    }

    @Test
    @DisplayName("embedded in scope but none at CURRENT_EMBED_REV → EMBED_REV_EMPTY (the T-C23 anomaly cause)")
    void embedRevEmpty() {
        assertThat(new SearchEmptyDiagnostics(12, 12, 0, 0).cause())
                .isEqualTo(SearchEmptyCause.EMBED_REV_EMPTY);
    }

    @Test
    @DisplayName("in scope at rev but every row validation-gated → VALIDATION_GATE_EMPTY (the T-C20 gate holding)")
    void validationGateEmpty() {
        assertThat(new SearchEmptyDiagnostics(12, 12, 12, 0).cause())
                .isEqualTo(SearchEmptyCause.VALIDATION_GATE_EMPTY);
    }

    @Test
    @DisplayName("eligible rows exist yet the search was empty → UNEXPECTED (fail loud)")
    void unexpectedWhenEligibleButEmpty() {
        assertThat(new SearchEmptyDiagnostics(12, 12, 12, 7).cause())
                .isEqualTo(SearchEmptyCause.UNEXPECTED);
    }

    @Test
    @DisplayName("funnel invariant violation (non-monotone counts) → UNEXPECTED, never a guessed label")
    void invariantViolationIsUnexpected() {
        assertThat(new SearchEmptyDiagnostics(5, 9, 3, 1).cause())
                .isEqualTo(SearchEmptyCause.UNEXPECTED);
        assertThat(new SearchEmptyDiagnostics(-1, 0, 0, 0).cause())
                .isEqualTo(SearchEmptyCause.UNEXPECTED);
    }

    @Test
    @DisplayName("partially-populated funnel still classifies at the first zero stage")
    void firstZeroStageWins() {
        // 10 in scope, 10 embedded, 0 at rev, 0 eligible → rev mismatch, not the gate
        assertThat(new SearchEmptyDiagnostics(10, 10, 0, 0).cause())
                .isEqualTo(SearchEmptyCause.EMBED_REV_EMPTY);
        // 10 in scope, 4 embedded, 4 at rev, 4 eligible would be UNEXPECTED;
        // 10 / 4 / 4 / 0 → the gate
        assertThat(new SearchEmptyDiagnostics(10, 4, 4, 0).cause())
                .isEqualTo(SearchEmptyCause.VALIDATION_GATE_EMPTY);
    }
}

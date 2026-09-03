package com.syllabai.smartmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cohen's κ hand-computed cases (Smart Mark release gate, F-161).
 */
class KappaAgreementServiceTest {

    @Test
    @DisplayName("the canonical 80% agreement case: κ = 0.60 (exactly the release threshold)")
    void canonicalCase() {
        // 10 point decisions: 4 both-yes, 4 both-no, 1 smart-only, 1 human-only
        List<int[]> pairs = List.of(
                new int[]{1, 1}, new int[]{1, 1}, new int[]{1, 1}, new int[]{1, 1},
                new int[]{0, 0}, new int[]{0, 0}, new int[]{0, 0}, new int[]{0, 0},
                new int[]{1, 0},
                new int[]{0, 1});
        // po = 0.8; pe = 0.5*0.5 + 0.5*0.5 = 0.5; κ = (0.8-0.5)/(1-0.5) = 0.6
        var stats = KappaAgreementService.cohenKappa(pairs);
        assertThat(stats.observedAgreement()).isCloseTo(0.8, within(1e-12));
        assertThat(stats.kappa()).isCloseTo(0.6, within(1e-12));
        assertThat(stats.sampleSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("skewed agreement case: κ below the observed agreement")
    void skewedAgreement() {
        List<int[]> pairs = List.of(
                new int[]{1, 1}, new int[]{1, 1}, new int[]{1, 0}, new int[]{0, 1},
                new int[]{0, 0}, new int[]{0, 0});
        // po = 4/6 = 0.667; pe = (3/6*3/6)+(3/6*3/6) = 0.5; κ = 0.333/0.5 = 0.667
        var stats = KappaAgreementService.cohenKappa(pairs);
        assertThat(stats.observedAgreement()).isCloseTo(4.0 / 6.0, within(1e-12));
        assertThat(stats.kappa()).isCloseTo((4.0 / 6.0 - 0.5) / 0.5, within(1e-12));
    }

    @Test
    @DisplayName("all-agree-at-one-extreme: pe degenerates to 1, κ = 1 by convention")
    void degenerateMarginals() {
        List<int[]> pairs = List.of(
                new int[]{1, 1}, new int[]{1, 1}, new int[]{1, 1});
        var stats = KappaAgreementService.cohenKappa(pairs);
        assertThat(stats.kappa()).isEqualTo(1.0);
        assertThat(stats.observedAgreement()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("systematic disagreement (anti-agreement) yields κ = -1")
    void systematicDisagreement() {
        // smart yes exactly when human no; marginals identical
        List<int[]> pairs = List.of(
                new int[]{1, 0}, new int[]{1, 0}, new int[]{0, 1}, new int[]{0, 1});
        // po = 0; pe = 0.5*0.5 + 0.5*0.5 = 0.5; κ = -0.5/0.5 = -1
        var stats = KappaAgreementService.cohenKappa(pairs);
        assertThat(stats.kappa()).isCloseTo(-1.0, within(1e-12));
    }

    @Test
    @DisplayName("empty input is rejected loudly (never a silent κ)")
    void emptyRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> KappaAgreementService.cohenKappa(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

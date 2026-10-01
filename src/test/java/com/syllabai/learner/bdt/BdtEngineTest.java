package com.syllabai.learner.bdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BdtEngineTest {

    private final BdtEngine engine = new BdtEngine();
    private final BdtParams params = BdtParams.paperDefaults();   // prior=.3 held=.7 notHeld=.1

    @Test
    @DisplayName("prior default is the paper's 0.3")
    void paperPrior() {
        assertThat(params.prior()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("tagged distractor raises misconception probability")
    void taggedDistractorRaises() {
        double prior = 0.3;
        // posterior = 0.3*0.7 / (0.3*0.7 + 0.7*0.1) = 0.21 / 0.28 = 0.75
        double updated = engine.updateOnTaggedDistractor(prior, params);
        assertThat(updated).isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(updated).isGreaterThan(prior);
    }

    @Test
    @DisplayName("repeated distractor evidence converges to near-certainty")
    void repeatedEvidenceConverges() {
        double p = 0.3;
        for (int i = 0; i < 10; i++) {
            p = engine.updateOnTaggedDistractor(p, params);
        }
        assertThat(p).isGreaterThan(0.995);
    }

    @Test
    @DisplayName("a correct answer weakens the misconception")
    void correctWeakens() {
        double prior = 0.75;
        double updated = engine.updateOnCorrect(prior, params);
        // posterior = 0.75*0.3 / (0.75*0.3 + 0.25*0.9) = 0.225 / 0.45 = 0.5
        assertThat(updated).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(updated).isLessThan(prior);
    }

    @Test
    @DisplayName("likelihoods must be strictly informative")
    void rejectsUninformativeLikelihoods() {
        assertThatThrownBy(() -> new BdtParams(0.3, 0.1, 0.7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BdtParams(0.3, 0.5, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("active() thresholding for remediation offers")
    void activeThreshold() {
        assertThat(engine.active(0.6, 0.5)).isTrue();
        assertThat(engine.active(0.4, 0.5)).isFalse();
    }

    // -- staleness relaxation toward the prior (MED-2, ADR-032) --------------

    @Test
    @DisplayName("fresh evidence relaxes to the stored posterior itself")
    void freshEvidenceUnchanged() {
        Instant now = Instant.now();
        assertThat(engine.relaxedToPrior(0.9, 0.3, now.minusSeconds(60), now, Duration.ofDays(180)))
                // 60s of staleness at tau=180d shifts the value by ~2.3e-6 — "fresh" is approximate
                .isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    @DisplayName("future-dated evidence (clock skew) is taken at face value")
    void clockSkewTakenAtFaceValue() {
        Instant now = Instant.now();
        assertThat(engine.relaxedToPrior(0.9, 0.3, now.plusSeconds(3600), now, Duration.ofDays(180)))
                .isEqualTo(0.9);
    }

    @Test
    @DisplayName("ancient evidence relaxes to the population prior")
    void ancientEvidenceReachesPrior() {
        Instant now = Instant.now();
        double relaxed = engine.relaxedToPrior(0.9, 0.3,
                now.minus(Duration.ofDays(365 * 40)), now, Duration.ofDays(180));
        assertThat(relaxed).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("at age = tau the deviation has decayed by exactly e^-1")
    void oneTauScalesDeviationByE() {
        Instant now = Instant.now();
        double relaxed = engine.relaxedToPrior(0.9, 0.3,
                now.minus(Duration.ofDays(180)), now, Duration.ofDays(180));
        assertThat(relaxed).isCloseTo(0.3 + 0.6 * Math.exp(-1),
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("semigroup on the deviation: two relaxations compose into the single curve")
    void semigroupOnDeviation() {
        Instant anchor = Instant.now().minus(Duration.ofDays(300));
        Instant mid = anchor.plus(Duration.ofDays(120));
        Instant now = mid.plus(Duration.ofDays(180));
        Duration tau = Duration.ofDays(180);
        double twoStep = engine.relaxedToPrior(
                engine.relaxedToPrior(0.9, 0.3, anchor, mid, tau),
                0.3, mid, now, tau);
        double oneStep = engine.relaxedToPrior(0.9, 0.3, anchor, now, tau);
        assertThat(twoStep).isCloseTo(oneStep, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("relaxation is symmetric: a stale refutation drifts up toward the prior (relapse is real)")
    void staleRefutationDriftsUp() {
        Instant now = Instant.now();
        double relaxed = engine.relaxedToPrior(0.05, 0.3,
                now.minus(Duration.ofDays(365)), now, Duration.ofDays(180));
        assertThat(relaxed).isGreaterThan(0.05).isLessThan(0.3);
    }

    @Test
    @DisplayName("tau must be strictly positive")
    void tauMustBePositive() {
        Instant now = Instant.now();
        assertThatThrownBy(() ->
                engine.relaxedToPrior(0.9, 0.3, now.minusSeconds(1), now, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.syllabai.learner.bdt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}

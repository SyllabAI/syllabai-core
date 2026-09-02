package com.syllabai.learner.bkt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BKT engine unit tests (Master Spec §33: every domain policy and algorithm
 * unit-tested). Expected values are hand-computed from the standard BKT update.
 */
class BktEngineTest {

    private final BktEngine engine = new BktEngine();
    private final BktParams params = BktParams.paperDefaults();   // L0=.1 slip=.1 guess=.25 T=.1

    @Test
    @DisplayName("paper defaults match the research design (L0=0.1, slip=0.1, guess=0.25, T=0.1)")
    void paperDefaultsMatchResearchDesign() {
        assertThat(params.l0()).isEqualTo(0.1);
        assertThat(params.slip()).isEqualTo(0.1);
        assertThat(params.guess()).isEqualTo(0.25);
        assertThat(params.learnRate()).isEqualTo(0.1);
    }

    @Test
    @DisplayName("first correct answer: posterior then learning transition")
    void firstCorrectUpdate() {
        // P(L|correct) = 0.1*0.9 / (0.1*0.9 + 0.9*0.25) = 0.09 / 0.315 = 0.285714...
        double posterior = engine.posterior(0.1, true, params);
        assertThat(posterior).isCloseTo(0.09 / 0.315, org.assertj.core.data.Offset.offset(1e-12));

        // P(L_{t+1}) = posterior + (1-posterior)*0.1
        double updated = engine.update(0.1, true, params);
        assertThat(updated).isCloseTo(posterior + (1 - posterior) * 0.1,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(updated).isGreaterThan(0.1);
    }

    @Test
    @DisplayName("first incorrect answer: evidence drops, learning recovers a little")
    void firstIncorrectUpdate() {
        // P(L|incorrect) = 0.1*0.1 / (0.1*0.1 + 0.9*0.75) = 0.01 / 0.685 = 0.0145985...
        double posterior = engine.posterior(0.1, false, params);
        assertThat(posterior).isCloseTo(0.01 / 0.685, org.assertj.core.data.Offset.offset(1e-12));

        double updated = engine.update(0.1, false, params);
        assertThat(updated).isCloseTo(0.01 / 0.685 + (1 - 0.01 / 0.685) * 0.1,
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    @DisplayName("repeated correct answers converge to mastery")
    void convergesWithRepeatedCorrect() {
        double mastery = 0.1;
        for (int i = 0; i < 30; i++) {
            mastery = engine.update(mastery, true, params);
        }
        assertThat(mastery).isGreaterThan(0.99);
    }

    @Test
    @DisplayName("repeated incorrect answers do not reach zero with learning rate > 0")
    void floorWithRepeatedIncorrect() {
        double mastery = 0.9;
        for (int i = 0; i < 60; i++) {
            mastery = engine.update(mastery, false, params);
        }
        assertThat(mastery).isBetween(0.0, 0.2);
    }

    @Test
    @DisplayName("mastery stays within [0,1] even for degenerate inputs")
    void clampsBounds() {
        assertThat(engine.update(1.4, true, params)).isLessThanOrEqualTo(1.0);
        assertThat(engine.update(-0.2, false, params)).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("parameters outside (0,1) are rejected")
    void rejectsInvalidParams() {
        assertThatThrownBy(() -> new BktParams(0, 0.1, 0.25, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BktParams(0.1, 1.0, 0.25, 0.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("mastered() honours the threshold")
    void masteredThreshold() {
        assertThat(engine.mastered(0.95, 0.9)).isTrue();
        assertThat(engine.mastered(0.5, 0.9)).isFalse();
    }
}

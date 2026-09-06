package com.syllabai.learner.decay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Band boundaries are shared vocabulary (DecayParams.bandOf): strictly below
 * the low ceiling is LOW, at-or-above the high floor is SECURE, between is
 * DEVELOPING. Pinned because the state view, the personalized knowledge-graph
 * view and the dashboard must never disagree (F-034 reads the same method).
 */
class DecayParamsBandTest {

    private final DecayParams params = DecayParams.paperDefaults();  // 0.45 / 0.8

    @Test
    @DisplayName("band boundaries: strictly-below is LOW, at-or-above is SECURE")
    void boundaries() {
        assertThat(params.bandOf(0.0)).isEqualTo("LOW");
        assertThat(params.bandOf(0.4499)).isEqualTo("LOW");
        assertThat(params.bandOf(0.45)).isEqualTo("DEVELOPING");     // ceiling itself is NOT low
        assertThat(params.bandOf(0.79)).isEqualTo("DEVELOPING");
        assertThat(params.bandOf(0.8)).isEqualTo("SECURE");          // floor itself IS secure
        assertThat(params.bandOf(1.0)).isEqualTo("SECURE");
    }
}

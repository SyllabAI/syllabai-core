package com.syllabai.learner.decay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EbbinghausDecayServiceTest {

    private final EbbinghausDecayService service = new EbbinghausDecayService();
    private final DecayParams params = DecayParams.paperDefaults();

    @Test
    @DisplayName("no elapsed time → no decay")
    void noElapsedTime() {
        Instant now = Instant.now();
        assertThat(service.decayed(0.9, now, now, params)).isEqualTo(0.9);
        assertThat(service.decayed(0.9, now.plusSeconds(10), now, params)).isEqualTo(0.9);
    }

    @Test
    @DisplayName("P(t) = P0 * e^(-t/τ) with band-selected τ")
    void decayFormula() {
        Instant now = Instant.now();
        // mastery 0.5 → middle band (τ=90d); 90 days elapsed → e^-1
        double decayed = service.decayed(0.5, now.minus(Duration.ofDays(90)), now, params);
        assertThat(decayed).isCloseTo(0.5 * Math.exp(-1), org.assertj.core.data.Offset.offset(1e-9));

        // mastery 0.3 → low band (τ=30d); 30 days elapsed → e^-1 (stays above the 0.1 floor)
        double low = service.decayed(0.3, now.minus(Duration.ofDays(30)), now, params);
        assertThat(low).isCloseTo(0.3 * Math.exp(-1), org.assertj.core.data.Offset.offset(1e-9));

        // mastery 0.9 → high band (τ=365d); 365 days → e^-1
        double high = service.decayed(0.9, now.minus(Duration.ofDays(365)), now, params);
        assertThat(high).isCloseTo(0.9 * Math.exp(-1), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("decay never drops below the floor")
    void floorRespected() {
        Instant now = Instant.now();
        double decayed = service.decayed(0.12, now.minus(Duration.ofDays(3650)), now, params);
        assertThat(decayed).isEqualTo(params.floor());
    }

    @Test
    @DisplayName("strong mastery decays slower than weak mastery")
    void bandSlowerForStrongerMastery() {
        Instant now = Instant.now();
        Instant practiced = now.minus(Duration.ofDays(60));
        double weak = service.decayed(0.2, practiced, now, params);    // τ=30
        double strong = service.decayed(0.9, practiced, now, params);  // τ=365
        assertThat(weak / 0.2).isLessThan(strong / 0.9);               // larger relative loss when weak
    }

    @Test
    @DisplayName("review needed once effective mastery crosses the threshold")
    void reviewThreshold() {
        Instant now = Instant.now();
        // 0.9 at τ=365 after 365d → 0.331 < 0.6 → review
        assertThat(service.needsReview(0.9, now.minus(Duration.ofDays(365)), now, params)).isTrue();
        // 0.9 after 30 days → 0.9 * e^(-30/365) = 0.828 ≥ 0.6 → no review
        assertThat(service.needsReview(0.9, now.minus(Duration.ofDays(30)), now, params)).isFalse();
    }

    @Test
    @DisplayName("invalid band/ threshold configuration rejected")
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> new DecayParams(30, 90, 365, 0.9, 0.45, 0.1, 0.6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecayParams(30, 90, 365, 0.45, 0.8, 0.7, 0.6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

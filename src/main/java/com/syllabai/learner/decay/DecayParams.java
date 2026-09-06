package com.syllabai.learner.decay;

import java.time.Duration;
import java.time.Instant;

/**
 * Ebbinghaus forgetting-curve parameters (Master Spec §11, Paper B: decay applied
 * nightly, τ = 30/90/365 days by proficiency band).
 *
 * @param tauLowDays        τ for the lowest mastery band (days)
 * @param tauMidDays        τ for the middle band
 * @param tauHighDays       τ for the strongest band
 * @param lowBandCeiling    mastery below this is "low" (default 0.45)
 * @param highBandFloor     mastery at/above this is "high" (default 0.8)
 * @param floor             decayed mastery never falls below this (default = BKT L0 0.1)
 * @param reviewBelow       decayed mastery below this schedules a review (default 0.6)
 */
public record DecayParams(
        int tauLowDays, int tauMidDays, int tauHighDays,
        double lowBandCeiling, double highBandFloor,
        double floor, double reviewBelow) {

    public static DecayParams paperDefaults() {
        return new DecayParams(30, 90, 365, 0.45, 0.8, 0.1, 0.6);
    }

    public DecayParams {
        if (tauLowDays <= 0 || tauMidDays <= 0 || tauHighDays <= 0) {
            throw new IllegalArgumentException("tau values must be positive");
        }
        if (!(0.0 < lowBandCeiling && lowBandCeiling < highBandFloor && highBandFloor <= 1.0)) {
            throw new IllegalArgumentException("band thresholds must satisfy 0 < low < high <= 1");
        }
        if (!(0.0 <= floor && floor < reviewBelow && reviewBelow <= 1.0)) {
            throw new IllegalArgumentException("floor must be < reviewBelow, both in [0,1]");
        }
    }

    /** τ (as duration) for the band the given mastery falls into. */
    public Duration tauFor(double mastery) {
        double m = Math.max(0.0, Math.min(1.0, mastery));
        if (m < lowBandCeiling) {
            return Duration.ofDays(tauLowDays);
        }
        if (m < highBandFloor) {
            return Duration.ofDays(tauMidDays);
        }
        return Duration.ofDays(tauHighDays);
    }

    /**
     * Proficiency band label for a (typically decayed) mastery value — the band
     * vocabulary every learner-facing read model shares (LOW / DEVELOPING /
     * SECURE). The thresholds live here so the state view and the personalized
     * knowledge-graph view can never disagree. Strictly below the ceiling is
     * LOW; at-or-above the floor is SECURE.
     */
    public String bandOf(double mastery) {
        if (mastery < lowBandCeiling) {
            return "LOW";
        }
        if (mastery < highBandFloor) {
            return "DEVELOPING";
        }
        return "SECURE";
    }
}

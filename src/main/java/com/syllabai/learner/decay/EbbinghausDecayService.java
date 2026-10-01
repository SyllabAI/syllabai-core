package com.syllabai.learner.decay;

import java.time.Duration;
import java.time.Instant;

/**
 * Ebbinghaus forgetting applied to BKT mastery (Paper B §3.3, Master Spec §11):
 *
 * <pre>P(t) = P₀ · e^(−t/τ)</pre>
 *
 * <p>τ depends on the proficiency band of the value passed in (30/90/365 days) —
 * callers MUST pass the stored post-practice posterior P₀, which freezes the band
 * at practice time (ADR-031). Decay never lowers mastery below the configured floor
 * (= initial knowledge L₀), because the learner has demonstrably been exposed to
 * the skill — total erasure is not modelled. Pure domain class; the nightly job and
 * read models use it (§31 APPLY_FORGETTING_DECAY). The result is recomputed from
 * the anchor on every call and is never persisted (ADR-031): with a stable anchor,
 * the exponential semigroup (e^(−(t₁+t₂)/τ) = e^(−t₁/τ)·e^(−t₂/τ)) guarantees that
 * any number of passes compose into the exact single-pass curve.</p>
 */
public class EbbinghausDecayService {

    /**
     * @param mastery        stored mastery P₀ at {@code lastPracticed}
     * @param lastPracticed  when mastery was last updated
     * @param now            evaluation time
     * @param params         decay configuration
     */
    public double decayed(double mastery, Instant lastPracticed, Instant now, DecayParams params) {
        if (!now.isAfter(lastPracticed)) {
            return clamp01(mastery);
        }
        Duration elapsed = Duration.between(lastPracticed, now);
        Duration tau = params.tauFor(mastery);
        // nanosecond precision avoids sub-second truncation drift in the exponent
        double decayed = mastery * Math.exp(-(double) elapsed.toNanos() / tau.toNanos());
        return Math.max(params.floor(), clamp01(decayed));
    }

    /**
     * Whether the decayed mastery has crossed the review threshold and a review
     * should be scheduled (feeds {@code review_schedules}).
     */
    public boolean needsReview(double mastery, Instant lastPracticed, Instant now,
                               DecayParams params) {
        double effective = decayed(mastery, lastPracticed, now, params);
        return effective < params.reviewBelow();
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

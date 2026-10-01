package com.syllabai.learner.bdt;

import java.time.Duration;
import java.time.Instant;

/**
 * Bayesian misconception tracking driven by distractor evidence (Paper B §3.4,
 * Master Spec §11 BDT). Pure domain class — unit-testable in isolation.
 */
public class BdtEngine {

    /**
     * Posterior P(misconception | evidence) where evidence = "learner selected a
     * distractor tagged with this misconception".
     */
    public double updateOnTaggedDistractor(double prior, BdtParams params) {
        double p = clamp01(prior);
        double posterior = p * params.selectIfHeld()
                / (p * params.selectIfHeld() + (1 - p) * params.selectIfNotHeld());
        return clamp01(posterior);
    }

    /**
     * Evidence that the misconception was NOT expressed — a correct answer on a
     * question whose distractors are tagged with the misconception weakens it.
     */
    public double updateOnCorrect(double prior, BdtParams params) {
        // P(not held | correct) ∝ P(correct | held)·P(held) with P(correct|held)=1-selectIfHeld
        double p = clamp01(prior);
        double pHeld = p;
        double pNot = 1 - p;
        double likeIfHeld = 1 - params.selectIfHeld();
        double likeIfNot = 1 - params.selectIfNotHeld();
        double posterior = pHeld * likeIfHeld
                / (pHeld * likeIfHeld + pNot * likeIfNot);
        return clamp01(posterior);
    }

    /** Conventionally "active" misconception — drives remediation offers. */
    public boolean active(double probability, double threshold) {
        return clamp01(probability) >= threshold;
    }

    /**
     * Staleness relaxation of a stored BDT posterior toward the population prior
     * (MED-2, ADR-032). The stored {@code posterior} is the evidence-anchored
     * estimate P(held) at {@code lastEvidenceAt}; as that evidence ages it says
     * less about the learner <em>now</em>, so the belief about the current learner
     * relaxes back to the base rate:
     *
     * <pre>effective = prior + (P_e − prior) · e^(−age/τ_s)</pre>
     *
     * <p>This models <strong>evidence ageing, not belief evaporation</strong>: an
     * unaddressed misconception does not fade on a clock, so the relaxation is
     * symmetric around the prior — a stale 0.9 drifts down toward the base rate
     * AND a stale refutation drifts up (relapse is real; re-diagnosis happens
     * naturally on the next related attempt, which re-runs the BDT update).</p>
     *
     * <p>The result is recomputed from the anchor on every call and is never
     * persisted (ADR-032, same architecture as ADR-031 for mastery): with a
     * stable anchor pair (P_e, lastEvidenceAt) the exponential semigroup acts on
     * the deviation (effective₁ − prior = dev·e^(−t₁/τ), then ·e^(−t₂/τ) again),
     * so any number of recompositions equal the exact single-relaxation curve.
     * There is deliberately no nightly job for misconceptions.</p>
     *
     * @param posterior      stored P(held) at {@code lastEvidenceAt} (may exceed the prior in either direction)
     * @param prior          population base rate for this misconception (current configured prior)
     * @param lastEvidenceAt when the posterior was last updated by evidence
     * @param now            evaluation time
     * @param tau            staleness time constant τ_s (e.g. 180 days); must be positive
     */
    public double relaxedToPrior(double posterior, double prior,
                                 Instant lastEvidenceAt, Instant now, Duration tau) {
        if (tau == null || tau.isZero() || tau.isNegative()) {
            throw new IllegalArgumentException("staleness tau must be positive, got " + tau);
        }
        double p = clamp01(posterior);
        double base = clamp01(prior);
        if (!now.isAfter(lastEvidenceAt)) {
            return p;   // fresh or clock-skewed evidence: full posterior
        }
        // nanosecond precision mirrors EbbinghausDecayService (no sub-second truncation drift)
        double ageNanos = Duration.between(lastEvidenceAt, now).toNanos();
        double dev = p - base;
        double relaxed = base + dev * Math.exp(-ageNanos / (double) tau.toNanos());
        return clamp01(relaxed);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

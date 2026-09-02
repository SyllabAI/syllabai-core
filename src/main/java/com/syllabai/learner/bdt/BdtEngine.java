package com.syllabai.learner.bdt;

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

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

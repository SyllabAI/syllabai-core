package com.syllabai.learner.bkt;

/**
 * Standard Bayesian Knowledge Tracing update (Corbett & Anderson, 1995), as specified
 * by Paper B §3.3 and Master Spec §11.
 *
 * <p>Pure domain class — no framework dependencies — so the science is unit-testable
 * in isolation (Master Spec §33).</p>
 */
public class BktEngine {

    /**
     * Posterior P(L_t | observation) after an attempt, before learning transition.
     *
     * @param prior   current mastery estimate P(L_t) ∈ [0,1]
     * @param correct observed correctness
     * @param params  BKT parameters
     */
    public double posterior(double prior, boolean correct, BktParams params) {
        double p = clamp01(prior);
        double evidence;
        if (correct) {
            evidence = p * (1 - params.slip())
                    / (p * (1 - params.slip()) + (1 - p) * params.guess());
        } else {
            evidence = p * params.slip()
                    / (p * params.slip() + (1 - p) * (1 - params.guess()));
        }
        return clamp01(evidence);
    }

    /**
     * Next-step mastery P(L_{t+1}) including the learning transition:
     * P(L_{t+1}) = posterior + (1 − posterior) · T.
     */
    public double update(double prior, boolean correct, BktParams params) {
        double posterior = posterior(prior, correct, params);
        return clamp01(posterior + (1 - posterior) * params.learnRate());
    }

    /**
     * Conventionally "mastered" band (used for decay τ selection and UI).
     * Master Spec leaves banding as a v0 heuristic pending calibration.
     */
    public boolean mastered(double mastery, double threshold) {
        return clamp01(mastery) >= threshold;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

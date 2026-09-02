package com.syllabai.learner.bkt;

/**
 * Bayesian Knowledge Tracing parameters (Master Spec §11 / Paper B research design).
 *
 * <p>These are research-design parameters, versioned through the {@code model_versions}
 * registry and configurable via {@code syllabai.learner.bkt.*} — never hard-coded in
 * domain logic (Master Spec §11: "configurable/versioned rather than hard-coded").</p>
 *
 * @param l0       initial knowledge probability P(L₀)
 * @param slip     probability of a wrong answer despite knowing
 * @param guess    probability of a right answer despite not knowing
 * @param learnRate probability of transitioning unlearned→learned per opportunity
 */
public record BktParams(double l0, double slip, double guess, double learnRate) {

    /** Paper B Cycle-1 research design: L₀=0.1, slip=0.1, guess=0.25, T=0.1. */
    public static BktParams paperDefaults() {
        return new BktParams(0.1, 0.1, 0.25, 0.1);
    }

    public BktParams {
        requireProbability("l0", l0);
        requireProbability("slip", slip);
        requireProbability("guess", guess);
        requireProbability("learnRate", learnRate);
        if (l0 + guess >= 1.0 && slip + guess >= 1.0) {
            // degenerate: can never distinguish knowledge states
            throw new IllegalArgumentException(
                    "BKT params degenerate (L0+guess and slip+guess both >= 1)");
        }
    }

    private static void requireProbability(String name, double value) {
        if (value <= 0.0 || value >= 1.0) {
            throw new IllegalArgumentException(
                    "BKT parameter %s must be in (0,1), got %s".formatted(name, value));
        }
    }
}

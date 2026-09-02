package com.syllabai.learner.bdt;

/**
 * Misconception-tracking (BDT) parameters (Master Spec §11 BDT, Paper B §3.4).
 *
 * @param prior        initial misconception probability (Paper B: 0.3)
 * @param selectIfHeld     P(chooses the tagged distractor | misconception held)
 * @param selectIfNotHeld  P(chooses the tagged distractor | misconception not held)
 */
public record BdtParams(double prior, double selectIfHeld, double selectIfNotHeld) {

    public static BdtParams paperDefaults() {
        // Paper B gives prior 0.3; likelihoods are v0 heuristics pending calibration
        return new BdtParams(0.3, 0.7, 0.1);
    }

    public BdtParams {
        requireProbability("prior", prior);
        requireProbability("selectIfHeld", selectIfHeld);
        requireProbability("selectIfNotHeld", selectIfNotHeld);
        if (selectIfHeld <= selectIfNotHeld) {
            throw new IllegalArgumentException(
                    "BDT likelihoods must be strictly informative: selectIfHeld > selectIfNotHeld");
        }
    }

    private static void requireProbability(String name, double value) {
        if (value <= 0.0 || value >= 1.0) {
            throw new IllegalArgumentException(
                    "BDT parameter %s must be in (0,1), got %s".formatted(name, value));
        }
    }
}

package com.syllabai.smartmark;

import com.syllabai.assessment.MarkPoint;

/**
 * One deterministic validation stage of the Smart Mark pipeline (Strategy pattern,
 * Master Spec §15 "LLM never final truth without validation"). Validators are pure:
 * they inspect the candidate against the scheme and report violations; they never
 * mutate, never call the LLM, and never invent marks.
 */
public interface MarkingValidator {

    /** short validator identity for violation messages and tests */
    String name();

    /**
     * @param candidate the generator's proposal
     * @param context   the marking context (answer, part, scheme, points)
     * @return an empty list when the candidate passes this stage
     */
    java.util.List<String> validate(MarkingCandidate candidate, MarkingContext context);

    /**
     * Shared helper: the mark bound for the in-scope points — the total marks the
     * scheme offers for this part (points are atomic, so the sum is the ceiling).
     */
    static int pointMarkCeiling(MarkingContext context) {
        return context.points().stream().mapToInt(MarkPoint::marks).sum();
    }
}

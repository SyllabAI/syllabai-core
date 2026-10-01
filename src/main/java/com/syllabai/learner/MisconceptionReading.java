package com.syllabai.learner;

/**
 * Read model for one misconception row with its staleness-relaxed probability
 * (MED-2, ADR-032). The stored {@link MisconceptionState} is the evidence
 * anchor — {@code probability} is the BDT posterior <em>at
 * {@code lastEvidenceAt}</em> and is never rewritten by reads; {@code
 * effectiveProbability} is recomputed on every call as
 * {@code prior + (P_e − prior)·e^(−age/τ_s)} and is what every gate, ranking
 * and display surface consumes. One semantics everywhere: there is no
 * raw-vs-effective split across surfaces (the S3-1 lesson from ADR-031).
 *
 * @param state                the anchored row (evidenceCount, ids, timestamps)
 * @param effectiveProbability staleness-relaxed P(held) at read time
 */
public record MisconceptionReading(MisconceptionState state, double effectiveProbability) {

    /** the relaxed probability — the value readers should gate, rank and show */
    public double effective() {
        return effectiveProbability;
    }

    public int evidenceCount() {
        return state.evidenceCount();
    }
}

package com.syllabai.retrieval;

/**
 * Evidence sufficiency hints for the retrieval fabric (T-C14; contract sketch
 * §2 — "sufficiency hints, segment reconstruction flags"). Downstream evidence
 * selection reads these; providers may use them as candidate-count guidance but
 * must never degrade correctness for them (an unsatisfiable hint yields fewer
 * candidates, never fabricated ones).
 *
 * @param minEvidenceCount                minimum distinct evidence items wanted
 * @param segmentReconstructionPreferred  Relevant Segment Extraction / local
 *                                        context reconstruction preferred over
 *                                        whole-chunk evidence (research doc P1)
 */
public record EvidenceRequirements(int minEvidenceCount,
                                   boolean segmentReconstructionPreferred) {

    public EvidenceRequirements {
        if (minEvidenceCount < 0) {
            throw new IllegalArgumentException("minEvidenceCount must be >= 0, got " + minEvidenceCount);
        }
    }

    /** Default: one distinct evidence item, no segment reconstruction. */
    public static EvidenceRequirements defaults() {
        return new EvidenceRequirements(1, false);
    }
}

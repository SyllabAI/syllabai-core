package com.syllabai.smartmark;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.QuestionPart;
import java.util.List;
import java.util.UUID;

/**
 * A generator's proposal for one answer — a <em>candidate</em>, never the truth.
 * The pipeline validates it deterministically before any marks are applied.
 *
 * @param modelId     model that generated the candidate (recorded in the result)
 * @param allocations one allocation per in-scope mark point
 * @param confidence  generator self-report 0–1 (may be null)
 * @param rawOutput   verbatim generator output retained for audit
 */
public record MarkingCandidate(String modelId, List<Allocation> allocations,
                               Double confidence, String rawOutput) {

    /**
     * @param markPointId the scheme point this allocation decides
     * @param ref         human-readable point reference
     * @param awarded     whether any of the point's marks were earned (derived:
     *                    {@code marksAwarded > 0} — the compact constructor
     *                    enforces it, so κ pairing can never disagree with the sum)
     * @param marksAwarded marks earned within this point, 0..point.marks. SME
     *                    schemes bundle several examiner sub-points ("[1 mark]"
     *                    annotations) into one multi-mark row; the generator
     *                    assesses each sub-point independently and returns the
     *                    earned sum — partial credit, not whole-point all-or-nothing
     * @param evidence    quoted learner text supporting the decision (explanation §15)
     * @param rationale   why the marks were (not) awarded — for multi-mark points,
     *                    which sub-points were earned and which were missed
     */
    public record Allocation(UUID markPointId, String ref, boolean awarded, int marksAwarded,
                             String evidence, String rationale) {

        public Allocation {
            if (marksAwarded < 0) {
                marksAwarded = 0;
            }
            awarded = marksAwarded > 0;
        }

        /** canonical shape: marks are the single source of truth, awarded derives */
        public Allocation(UUID markPointId, String ref, int marksAwarded,
                          String evidence, String rationale) {
            this(markPointId, ref, marksAwarded > 0, marksAwarded, evidence, rationale);
        }
    }
}

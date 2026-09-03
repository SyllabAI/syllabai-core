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
     * @param awarded     whether the point is earned (points are atomic)
     * @param evidence    quoted learner text supporting the decision (explanation §15)
     * @param rationale   why the point was (not) awarded
     */
    public record Allocation(UUID markPointId, String ref, boolean awarded,
                             String evidence, String rationale) {
    }
}

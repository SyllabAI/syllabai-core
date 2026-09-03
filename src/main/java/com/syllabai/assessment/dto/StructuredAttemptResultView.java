package com.syllabai.assessment.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of a structured submission: the attempt is stored PENDING marking —
 * feedback arrives when Smart Mark (κ-gated) or a teacher produces marks
 * (Master Spec §15). Marks are null while pending.
 */
public record StructuredAttemptResultView(
        UUID attemptId,
        UUID questionId,
        int marksPossible,
        String markingState,
        Instant submittedAt,
        List<PartResult> parts) {

    public record PartResult(UUID partId, String label, int marksPossible,
                             String markingState, Integer marksAwarded) {
    }
}

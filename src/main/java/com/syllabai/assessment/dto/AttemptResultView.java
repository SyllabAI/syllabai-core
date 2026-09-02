package com.syllabai.assessment.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Result of a submitted attempt — immediate feedback plus the misconception hint
 * the learner's distractor indicates (Paper B tutor loop).
 */
public record AttemptResultView(
        UUID attemptId,
        UUID questionId,
        boolean correct,
        int marksAwarded,
        int marksTotal,
        String correctOptionLabel,
        List<UUID> implicatedMisconceptionIds,
        Instant submittedAt) {
}

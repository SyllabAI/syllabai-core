package com.syllabai.assessment.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One part-level written answer inside a {@link StructuredSubmitRequest}.
 *
 * @param partId     the {@code question_parts.id} being answered
 * @param answerText the learner's written answer (may be empty = skipped)
 */
public record PartAnswerRequest(
        @NotNull UUID partId,
        String answerText) {
}

package com.syllabai.assessment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * @param questionId       the question being answered
 * @param chosenOptionId   selected MCQ option
 * @param responseTimeMs   client-measured time from render to submission
 * @param confidence       learner-reported confidence 1–5 (Paper B §3.5)
 * @param selfDoubtFlag    learner self-doubt flag (Paper B §3.5)
 * @param timedCondition   answered under timed conditions (Paper B §16)
 */
public record SubmitAnswerRequest(
        @NotNull UUID questionId,
        @NotNull UUID chosenOptionId,
        @NotNull @Min(0) Long responseTimeMs,
        @Min(1) @Max(5) Integer confidence,
        boolean selfDoubtFlag,
        boolean timedCondition) {
}

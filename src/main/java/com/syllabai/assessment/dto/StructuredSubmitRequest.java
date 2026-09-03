package com.syllabai.assessment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Submission of a multi-part STRUCTURED question (Master Spec §6.5, §16): one
 * written answer per question part, plus the Paper B §3.5 research fields and
 * the timed/untimed condition tag (F-162 paired conditions).
 *
 * @param questionId     the structured question
 * @param partAnswers   exactly one answer per part of the current version
 * @param responseTimeMs client-measured time from render to submission
 * @param confidence     learner-reported confidence 1–5
 * @param selfDoubtFlag  learner self-doubt flag
 * @param timedCondition answered under timed conditions
 */
public record StructuredSubmitRequest(
        @NotNull UUID questionId,
        @NotEmpty @Valid List<PartAnswerRequest> partAnswers,
        @NotNull @Min(0) Long responseTimeMs,
        @Min(1) @Max(5) Integer confidence,
        boolean selfDoubtFlag,
        boolean timedCondition) {
}

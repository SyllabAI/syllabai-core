package com.syllabai.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A learner opened the "Improve my answer" coaching generation on a
 * smart-marked answer (student Smart Mark surface). Companion of
 * {@link SmartFeedbackExplainedEvent} — same ephemerality and rationale: the
 * event records that improvement coaching was consumed; the prose is not
 * persisted.
 *
 * @param answerId          the smart-marked answer
 * @param attemptId         the owning attempt
 * @param learnerId         the requesting learner
 * @param questionId        the question
 * @param smartMarkResultId the accepted result the plan is grounded on
 * @param modelId           model that generated the plan (§19)
 * @param occurredAt        request time
 */
public record SmartImprovementPlanViewedEvent(
        UUID answerId,
        UUID attemptId,
        UUID learnerId,
        UUID questionId,
        UUID smartMarkResultId,
        String modelId,
        Instant occurredAt) {
}

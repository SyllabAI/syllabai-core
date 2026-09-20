package com.syllabai.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A learner opened the "Explain my feedback" generation on a smart-marked
 * answer (student Smart Mark surface). Append-only research telemetry: the
 * generation itself is ephemeral (regenerated per request), so this event is
 * the only durable record that the explanation was consumed — feeding the
 * Paper B engagement fields without storing derived LLM prose.
 *
 * @param answerId          the smart-marked answer
 * @param attemptId         the owning attempt
 * @param learnerId         the requesting learner
 * @param questionId        the question
 * @param smartMarkResultId the accepted result the explanation is grounded on
 * @param modelId           model that generated the explanation (§19)
 * @param occurredAt        request time
 */
public record SmartFeedbackExplainedEvent(
        UUID answerId,
        UUID attemptId,
        UUID learnerId,
        UUID questionId,
        UUID smartMarkResultId,
        String modelId,
        Instant occurredAt) {
}

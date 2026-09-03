package com.syllabai.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * BDT misconception probability was updated for one (learner, misconception) pair
 * (Master Spec §11, §12 evidence contract). The research module persists a
 * {@code BDT_UPDATED} telemetry row (§18) by observing this event.
 *
 * @param learnerId            learner whose misconception estimate changed
 * @param attemptId            attempt that produced the evidence
 * @param misconceptionNodeId  misconception node the estimate belongs to
 * @param priorProbability     P(misconception held) before the update
 * @param posteriorProbability P(misconception held) after the update
 * @param expressed            true when the learner chose a distractor tagged with
 *                             this misconception (strengthens the belief); false when
 *                             a correct answer on an item that monitors the
 *                             misconception weakened it (Paper B §3.4)
 * @param occurredAt           when the update was applied
 */
public record MisconceptionUpdatedEvent(
        UUID learnerId,
        UUID attemptId,
        UUID misconceptionNodeId,
        double priorProbability,
        double posteriorProbability,
        boolean expressed,
        Instant occurredAt) {
}

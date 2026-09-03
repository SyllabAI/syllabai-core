package com.syllabai.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * BKT mastery was updated for one (learner, node) pair (Master Spec §11, §12 evidence
 * contract). Published by the learner model after each Bayesian update; the research
 * module persists a {@code BKT_UPDATED} telemetry row (§18) by observing this event.
 *
 * @param learnerId        learner whose mastery changed
 * @param attemptId        attempt that produced the evidence
 * @param nodeId           knowledge-graph node the mastery estimate belongs to
 * @param priorMastery     P(L) before the update
 * @param posteriorMastery P(L) after the update (including the learning transition)
 * @param correctness      whether the observed attempt was correct
 * @param attempts         total attempts on this node after the update
 * @param correctCount     total correct attempts after the update
 * @param occurredAt       when the update was applied
 */
public record MasteryUpdatedEvent(
        UUID learnerId,
        UUID attemptId,
        UUID nodeId,
        double priorMastery,
        double posteriorMastery,
        boolean correctness,
        int attempts,
        int correctCount,
        Instant occurredAt) {
}

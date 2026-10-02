package com.syllabai.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * BKT mastery was updated for one (learner, node) pair (Master Spec §11, §12 evidence
 * contract). Published by the learner model after each Bayesian update; the research
 * module persists a {@code BKT_UPDATED} telemetry row (§18) by observing this event.
 *
 * <p>S2 challenge C1 (ADR-033): the update prior is the <strong>computed,
 * never-persisted pre-attempt forecast</strong> — the stored anchor decayed over the
 * practice gap — not the raw anchor. Both quantities are carried so the calibration
 * instrument can score the forecast the model actually acted on, while
 * {@code priorMastery} keeps its historical anchor semantics for S1-style forensics.</p>
 *
 * @param learnerId        learner whose mastery changed
 * @param attemptId        attempt that produced the evidence
 * @param nodeId           knowledge-graph node the mastery estimate belongs to
 * @param priorMastery     the stored decay anchor P₀ before the update (ADR-031) —
 *                         unchanged semantics, kept for anchor forensics
 * @param posteriorMastery P(L) after the update (including the learning transition);
 *                         becomes the new stored anchor
 * @param correctness      whether the observed attempt was correct
 * @param attempts         total attempts on this node after the update
 * @param correctCount     total correct attempts after the update
 * @param decayedPrior     the pre-attempt forecast the update actually consumed:
 *                         {@code decayed(P₀, lastPracticedAt, occurredAt)} — computed
 *                         at update time, never persisted; equals P₀ at zero gap
 * @param gapDays          whole days between {@code lastPracticedAt} and the attempt
 *                         (0 for fresh states; same truncation convention as
 *                         DECAY_APPLIED's {@code daysSinceLastPractice})
 * @param questionType     the question's format name (nullable for untyped evidence) —
 *                         emission context so the calibration report can price the
 *                         predicted emission per row (S2 challenge C2)
 * @param optionCount      the live option count for MCQ events, 0 otherwise
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
        double decayedPrior,
        long gapDays,
        String questionType,
        int optionCount,
        Instant occurredAt) {
}

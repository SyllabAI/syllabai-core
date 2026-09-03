package com.syllabai.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Ebbinghaus forgetting decay was applied to one (learner, node) mastery estimate by
 * the nightly job (Master Spec §11, §31 APPLY_FORGETTING_DECAY). The research module
 * persists a {@code DECAY_APPLIED} telemetry row (§18) by observing this event.
 *
 * @param learnerId             learner whose mastery decayed
 * @param nodeId                knowledge-graph node the mastery estimate belongs to
 * @param priorMastery          P(L) before decay
 * @param decayedMastery        P(L) after decay (never below the configured floor)
 * @param daysSinceLastPractice whole days since the last practice/evidence on this node
 * @param tauDays               τ used for this decay, by proficiency band (30/90/365)
 * @param reviewThresholdCrossed whether the decayed mastery crossed the review threshold
 * @param occurredAt            when the job applied the decay
 */
public record DecayAppliedEvent(
        UUID learnerId,
        UUID nodeId,
        double priorMastery,
        double decayedMastery,
        long daysSinceLastPractice,
        int tauDays,
        boolean reviewThresholdCrossed,
        Instant occurredAt) {
}

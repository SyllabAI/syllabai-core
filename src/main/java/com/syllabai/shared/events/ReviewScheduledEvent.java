package com.syllabai.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A review was scheduled for a (learner, node) pair — produced by the forgetting-decay
 * job when decayed mastery crosses the review threshold (Master Spec §11, §24 table
 * {@code review_schedules}). The research module persists a {@code REVIEW_SCHEDULED}
 * telemetry row (§18) by observing this event.
 *
 * @param learnerId        learner the review belongs to
 * @param nodeId           knowledge-graph node to review
 * @param dueAt            when the review becomes due
 * @param masteryAtTrigger decayed mastery that triggered the review
 * @param reason           trigger reason, e.g. "DECAY_CROSSED_THRESHOLD"
 * @param occurredAt       when the review was scheduled
 */
public record ReviewScheduledEvent(
        UUID learnerId,
        UUID nodeId,
        Instant dueAt,
        double masteryAtTrigger,
        String reason,
        Instant occurredAt) {
}

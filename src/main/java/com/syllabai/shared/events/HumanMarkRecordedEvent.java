package com.syllabai.shared.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A teacher recorded or overrode a human mark (Master Spec §15; V9 telemetry
 * extension). Human marks are the authoritative grade; when they revise an already
 * authoritative mark the event records the override (research-visible) — BKT evidence
 * for the attempt is never re-fired.
 *
 * @param answerId     the marked answer
 * @param attemptId    the owning attempt
 * @param learnerId    the learner
 * @param questionId   the question
 * @param marksAwarded the authoritative marks
 * @param revising     true when this mark revises a previous authoritative mark
 * @param markerId     the teacher
 * @param occurredAt   mark time
 */
public record HumanMarkRecordedEvent(
        UUID answerId,
        UUID attemptId,
        UUID learnerId,
        UUID questionId,
        int marksAwarded,
        boolean revising,
        UUID markerId,
        Instant occurredAt) {
}

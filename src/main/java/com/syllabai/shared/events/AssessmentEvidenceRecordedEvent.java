package com.syllabai.shared.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The assessment-to-learner evidence contract (Master Spec §12).
 *
 * <p>Assessment <strong>emits evidence</strong> as a domain event; it never mutates
 * learner state directly. The learner model (BKT/BDT, Master Spec §11) and the research
 * telemetry store (§18) observe this event independently — this is the Observer pattern
 * required by §23 and it keeps assessment, learner modelling and research
 * instrumentation decoupled.</p>
 *
 * @param attemptId        id of the persisted attempt
 * @param learnerId        user id of the learner
 * @param questionId       question that was attempted
 * @param topicNodeIds     knowledge-graph nodes the question tests (primary first)
 * @param correctness      whether the answer was correct
 * @param marksTotal       marks available for the question
 * @param marksAwarded     marks awarded (raw score; equals marksTotal/0 for MCQ v0)
 * @param responseTimeMs   time from question display to submission
 * @param confidence       learner-reported confidence (1–5, nullable)
 * @param selfDoubtFlag    learner self-doubt flag (Paper B §3.5, struggle type 4 signal)
 * @param timedCondition   true when answered under timed conditions (Paper B §16)
 * @param misconceptionIds misconception nodes implicated by the chosen distractor
 * @param provenance       where this attempt came from (e.g. "web-quiz-v0")
 * @param occurredAt       when the attempt was submitted
 */
public record AssessmentEvidenceRecordedEvent(
        UUID attemptId,
        UUID learnerId,
        UUID questionId,
        List<UUID> topicNodeIds,
        boolean correctness,
        int marksTotal,
        int marksAwarded,
        long responseTimeMs,
        Integer confidence,
        boolean selfDoubtFlag,
        boolean timedCondition,
        List<UUID> misconceptionIds,
        String provenance,
        Instant occurredAt) {
}

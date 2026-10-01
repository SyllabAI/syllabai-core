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
 * @param questionType     the question format's enum name — "MCQ_SINGLE",
 *                         "SHORT_ANSWER" or "STRUCTURED" (S2/ADR-033: the
 *                         learner model's emission model is format-aware — a
 *                         4-option MCQ is guessable, a marked multi-part
 *                         structured answer is not). Nullable only for legacy
 *                         publishers that never knew the format; consumers fall
 *                         back to the configured paper-default guess.
 * @param optionCount      number of answer options on an MCQ_SINGLE question
 *                         (the per-item guess base is 1/optionCount); 0 when
 *                         the format is not MCQ or is unknown.
 * @param topicNodeIds     knowledge-graph nodes the question tests (primary first)
 * @param specPointNodeIds the question's mapped spec-point nodes (T-C18 mapping,
 *                         ADR-026/V30 {@code question_spec_points}) — the SAME
 *                         marked-attempt evidence, fired at spec-point granularity
 *                         so the learner model can honestly paint points, not just
 *                         topics. Empty on unmapped questions (topic-only firing).
 *                         Processed identically to {@code topicNodeIds} by the
 *                         learner model (same BKT update, same decay, same review
 *                         scheduling); research telemetry stays topic-scoped.
 * @param correctness      whether the answer was correct
 * @param marksTotal       marks available for the question
 * @param marksAwarded     marks awarded (raw score; equals marksTotal/0 for MCQ v0)
 * @param responseTimeMs   time from question display to submission
 * @param confidence       learner-reported confidence (1–5, nullable)
 * @param selfDoubtFlag    learner self-doubt flag (Paper B §3.5, struggle type 4 signal)
 * @param timedCondition   true when answered under timed conditions (Paper B §16)
 * @param misconceptionIds misconception nodes <em>expressed</em> by the chosen
 *                        distractor (empty when the answer is correct or the chosen
 *                        option is untagged — strengthens the belief, Paper B §3.4)
 * @param observedMisconceptionIds every misconception node monitored by this
 *                        question's distractors — a correct answer weakens these
 *                        (BDT update-on-correct, Paper B §3.4)
 * @param provenance       where this attempt came from (e.g. "web-quiz-v0")
 * @param occurredAt       when the attempt was submitted
 */
public record AssessmentEvidenceRecordedEvent(
        UUID attemptId,
        UUID learnerId,
        UUID questionId,
        String questionType,
        int optionCount,
        List<UUID> topicNodeIds,
        List<UUID> specPointNodeIds,
        boolean correctness,
        int marksTotal,
        int marksAwarded,
        long responseTimeMs,
        Integer confidence,
        boolean selfDoubtFlag,
        boolean timedCondition,
        List<UUID> misconceptionIds,
        List<UUID> observedMisconceptionIds,
        String provenance,
        Instant occurredAt) {
}

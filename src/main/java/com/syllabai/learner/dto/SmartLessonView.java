package com.syllabai.learner.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Smart Lesson MVP (productization sprint §2): ONE explainable next action for
 * a learner on a selected topic, derived deterministically from the SAME
 * evidence the NBA engine consumes (mastery, misconceptions, review schedules,
 * fluency, tutor engagements, prerequisite relations). No new learner model —
 * this is a topic-focused projection of the existing recommendation state.
 *
 * <p>Every recommendation is traceable: {@code evidence} carries the exact
 * learner-state facts the decision used, {@code topicStatus} is the honest
 * diagnosis snapshot, and {@code action.reasonDetail} is human-readable.
 * The loop is closed by construction: completing the action produces new
 * evidence (attempt → BKT update / tutor engagement), which changes the next
 * call's decision.</p>
 */
public record SmartLessonView(
        UUID learnerId,
        UUID rootId,
        UUID topicNodeId,
        String topicCode,
        String topicTitle,
        Instant asOf,
        String policy,
        LessonActionView action,
        TopicStatusView topicStatus,
        List<PrerequisiteStatusView> prerequisites,
        List<MisconceptionStatusView> misconceptions,
        List<EvidenceFactView> evidence) {

    public static final String POLICY_ID = "smart-lesson/v1";

    /** What the learner should do next on (or before) this topic. */
    public enum ActionType {
        /** a measured-weak prerequisite must be strengthened first */
        REMEDIATE_PREREQUISITE,
        /** study the corrective concept a validated REMEDIATED_BY edge names */
        STUDY_CORRECTIVE,
        /** ask the Tutor for a grounded explanation of an active misconception */
        ASK_TUTOR,
        /** retrieval practice on this topic (review schedule due) */
        REVIEW_TOPIC,
        /** practise under timed conditions (measured fluency gap) */
        TIMED_PRACTICE,
        /** practise the topic's servable questions (weak mastery / tutor-engaged / start) */
        PRACTISE_QUESTIONS,
        /** the topic is mastered — move to the next topic in curriculum order */
        ADVANCE_TOPIC
    }

    /** Which deterministic rule produced the action (auditable). */
    public enum ReasonCode {
        PREREQUISITE_WEAK,
        MISCONCEPTION_REMEDIATION,
        MISCONCEPTION_SUSPECTED,
        DUE_REVIEW,
        FLUENCY_GAP,
        LOW_MASTERY,
        TUTOR_ENGAGED,
        INSUFFICIENT_COVERAGE,
        TOPIC_MASTERED
    }

    /**
     * The one next action. {@code targetNodeId} may be a prerequisite or
     * corrective node rather than the selected topic (the ladder honestly
     * redirects). {@code questionId} is the deterministic starter question
     * (first servable question of the target topic, difficulty order) whenever
     * the action involves practice.
     */
    public record LessonActionView(
            ActionType actionType,
            ReasonCode reasonCode,
            UUID targetNodeId,
            String targetCode,
            String targetTitle,
            UUID questionId,
            int servableQuestionCount,
            String reasonDetail) {
    }

    /** Honest diagnosis snapshot for the selected topic (measured facts only). */
    public record TopicStatusView(
            String coverage,            // UNMEASURED | PARTIAL | ESTABLISHED
            int attempts,
            Double mastery,             // raw BKT mastery, null when unmeasured
            Double effectiveMastery,    // decay-adjusted, null when unmeasured
            boolean reviewDue,
            Double strongestMisconceptionProbability,
            Double fluencyGap,
            long tutorAsks,
            int servableQuestions) {
    }

    /** One traceability fact the decision used (deterministic, no inference). */
    public record EvidenceFactView(String key, String value) {
    }

    /**
     * One direct prerequisite of the selected topic with the learner's
     * measured status as an overlay (null mastery = not yet measured — an
     * honest gap, never an invented value).
     */
    public record PrerequisiteStatusView(
            UUID nodeId, String code, String title,
            Double effectiveMastery, Integer attempts, boolean measuredWeak) {
    }

    /**
     * One misconception attached to the topic: the KG node plus the learner's
     * BDT probability overlay and, when a validated REMEDIATED_BY edge names
     * one, the corrective concept to study.
     */
    public record MisconceptionStatusView(
            UUID nodeId, String code, String title,
            Double probability, boolean active, String remediationNodeCode) {
    }
}

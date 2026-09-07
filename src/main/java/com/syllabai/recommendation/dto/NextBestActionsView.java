package com.syllabai.recommendation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Learner-facing next-best-learning-action response (F-092 minimal slice,
 * ADR-017): ranked learning actions, each carrying a structured reason code and
 * an evidence-derived reason detail. Recommendations are ADVICE derived from
 * measured evidence — they are deliberately distinct from the measured-facts
 * panels of the dashboard/learner state, and no reason here is a causal claim
 * (RECOMMENDATION_SYSTEM_ARCHITECTURE.md §14).
 */
public record NextBestActionsView(
        UUID learnerId,
        UUID rootId,
        Instant asOf,
        String policy,
        List<NextBestActionView> actions) {

    /** What the learner should do. */
    public enum ActionType {
        /** retrieval practice on a topic whose review is due (decay-triggered) */
        REVIEW_TOPIC,
        /** practice the validated questions mapped to a weak/uncovered topic */
        PRACTISE_QUESTIONS,
        /** remediate a prerequisite that a weak dependent topic depends on */
        REVIEW_PREREQUISITE,
        /** retry a specific previously low-mark question (still servable) */
        RETRY_PROBLEM_QUESTION,
        /** ask the Tutor for a grounded explanation (misconception / self-doubt) */
        ASK_TUTOR,
        /** practise under timed conditions to close a measured fluency gap */
        TIMED_EXERCISE
    }

    /** Which deterministic rule produced the action (auditable, evidence-backed). */
    public enum ReasonCode {
        DUE_REVIEW,
        PREREQUISITE_WEAK,
        PROBLEM_QUESTION,
        MISCONCEPTION_SUSPECTED,
        FLUENCY_GAP,
        LOW_MASTERY,
        UNCOVERED_TOPIC
    }

    /**
     * One ranked action. {@code questionId} is non-null only for
     * {@link ActionType#RETRY_PROBLEM_QUESTION}. {@code servableQuestionCount}
     * is the number of validated questions currently mapped to the target
     * topic (0 ⇒ the UI must show its honest no-validated-questions state).
     */
    public record NextBestActionView(
            int rank,
            ActionType actionType,
            ReasonCode reasonCode,
            UUID targetNodeId,
            String targetCode,
            String targetTitle,
            UUID questionId,
            int servableQuestionCount,
            String reasonDetail) {
    }
}

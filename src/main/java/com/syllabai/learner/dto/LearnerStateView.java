package com.syllabai.learner.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LearnerStateView(
        UUID learnerId,
        List<SkillStateView> skillStates,
        List<MisconceptionStateView> misconceptionStates,
        List<ReviewView> pendingReviews,
        List<TutorEngagementView> tutorEngagements,
        List<FlashcardRatingView> flashcardRatings) {

    public record ReviewView(UUID nodeId, Instant dueAt, String reason, String nodeName) {
    }

    /**
     * V47 pre-V48 rows compatibility constructor: the flashcard evidence class
     * (ADR-029 tranche 4.4) was added after the first consumers; this keeps any
     * caller still using the five-arg shape compiling with an honest empty list.
     */
    public LearnerStateView(UUID learnerId,
                            List<SkillStateView> skillStates,
                            List<MisconceptionStateView> misconceptionStates,
                            List<ReviewView> pendingReviews,
                            List<TutorEngagementView> tutorEngagements) {
        this(learnerId, skillStates, misconceptionStates, pendingReviews, tutorEngagements,
                List.of());
    }

    /**
     * V21 (P7): what the learner has been asking the Tutor about — grouped by
     * matched topic over the engagement window. Structured signal only (the
     * deterministic matcher's topic, ask count, last ask); the chat text stays
     * in the research log.
     */
    public record TutorEngagementView(
            UUID nodeId, String nodeTitle, long asks, Instant lastAskedAt, boolean refusedAny,
            java.util.Map<String, Long> signalCounts) {

        /** pre-V23 rows / empty state */
        public TutorEngagementView(UUID nodeId, String nodeTitle, long asks,
                                   Instant lastAskedAt, boolean refusedAny) {
            this(nodeId, nodeTitle, asks, lastAskedAt, refusedAny, java.util.Map.of());
        }
    }
}

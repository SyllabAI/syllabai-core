package com.syllabai.learner.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LearnerStateView(
        UUID learnerId,
        List<SkillStateView> skillStates,
        List<MisconceptionStateView> misconceptionStates,
        List<ReviewView> pendingReviews,
        List<TutorEngagementView> tutorEngagements) {

    public record ReviewView(UUID nodeId, Instant dueAt, String reason, String nodeName) {
    }

    /**
     * V21 (P7): what the learner has been asking the Tutor about — grouped by
     * matched topic over the engagement window. Structured signal only (the
     * deterministic matcher's topic, ask count, last ask); the chat text stays
     * in the research log.
     */
    public record TutorEngagementView(
            UUID nodeId, String nodeTitle, long asks, Instant lastAskedAt, boolean refusedAny) {
    }
}

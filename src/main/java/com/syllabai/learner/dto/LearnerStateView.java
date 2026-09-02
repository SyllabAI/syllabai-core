package com.syllabai.learner.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LearnerStateView(
        UUID learnerId,
        List<SkillStateView> skillStates,
        List<MisconceptionStateView> misconceptionStates,
        List<ReviewView> pendingReviews) {

    public record ReviewView(UUID nodeId, Instant dueAt, String reason) {
    }
}

package com.syllabai.learner.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Skill-mastery projection with the decay-adjusted effective mastery.
 *
 * @param effectiveMastery mastery after Ebbinghaus decay since last practice
 */
public record SkillStateView(
        UUID nodeId, double mastery, double effectiveMastery, String band,
        int attempts, int correctCount, Instant lastPracticedAt) {
}

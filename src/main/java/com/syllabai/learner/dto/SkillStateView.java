package com.syllabai.learner.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Skill-mastery projection with the decay-adjusted effective mastery and the
 * Paper B §16 fluency gap.
 *
 * @param effectiveMastery     mastery after Ebbinghaus decay since last practice
 * @param proceduralFluencyGap untimed accuracy − timed accuracy; null until the
 *                             learner has answered under BOTH conditions (F-162)
 * @param nodeName             human title of the KG node (P1 pilot UX); null when
 *                             the node row is absent — clients keep their own
 *                             id-based fallback
 */
public record SkillStateView(
        UUID nodeId, double mastery, double effectiveMastery, String band,
        int attempts, int correctCount, Instant lastPracticedAt,
        Double proceduralFluencyGap, String nodeName) {
}

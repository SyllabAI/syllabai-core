package com.syllabai.knowledge;

/**
 * Minimum edge types (Master Spec §7).
 *
 * <p>{@code TESTED_BY} is expressed relationally through {@code question_topics}
 * (questions are bank items, not graph nodes) — documented in V3 migration.</p>
 */
public enum RelationType {
    PART_OF,
    REQUIRES_PREREQUISITE,
    RELATED_TO,
    MISCONCEPTION_OF,
    EXPLAINED_BY,
    REMEDIATED_BY,
    /**
     * T-C11 settled store (V15): the wrong-answer pattern a misconception
     * manifests as, pointing at the concept whose questions expose it
     * (misconception → concept).
     */
    WRONG_ANSWER_PATTERN,
    /**
     * T-C11 settled store (V15): two concepts students commonly conflate
     * (concept → concept, symmetric in meaning, stored one-directionally).
     */
    COMMONLY_CONFUSED_WITH
}

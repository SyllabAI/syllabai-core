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
    REMEDIATED_BY
}

package com.syllabai.recommendation;

/**
 * Next-best-action rule thresholds (prefix {@code syllabai.recommendation}).
 *
 * <p>Defaults are documented v0 engineering choices for the deterministic
 * {@code nba-rules/v1} baseline (ADR-017: rule-based recommendations are the
 * canonical baseline; fixed constants here are product tuning parameters, not
 * scientific claims, and are overridable per environment).</p>
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "syllabai.recommendation")
public record RecommendationProperties(
        double weakMasteryCeiling,
        double fluencyGapThreshold,
        double problemMarkRatio,
        int minAttemptsForWeakness,
        int maxActions,
        int uncoveredTopicCap,
        int problemQuestionCap,
        int tutorEngagementWindowDays) {

    public RecommendationProperties {
        if (weakMasteryCeiling <= 0) weakMasteryCeiling = 0.45;   // matches decay LOW band ceiling
        if (fluencyGapThreshold <= 0) fluencyGapThreshold = 0.2;
        if (problemMarkRatio <= 0) problemMarkRatio = 0.5;
        if (minAttemptsForWeakness <= 0) minAttemptsForWeakness = 2;
        if (maxActions <= 0) maxActions = 8;
        if (uncoveredTopicCap <= 0) uncoveredTopicCap = 2;
        if (problemQuestionCap <= 0) problemQuestionCap = 2;
        if (tutorEngagementWindowDays <= 0) tutorEngagementWindowDays = 14; // P7: recency of a tutor ask
    }
}

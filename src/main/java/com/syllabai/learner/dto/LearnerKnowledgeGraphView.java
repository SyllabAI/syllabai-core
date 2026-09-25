package com.syllabai.learner.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The learner's personalized knowledge graph (F-034, Master Spec §22:
 * {@code GET /api/v1/learners/me/knowledge-graph?rootId=...}) — the curriculum
 * tree under a subject root, annotated with THIS learner's proficiency,
 * misconception and review state, plus the direct prerequisite relations among
 * the tree's nodes. One call feeds the mastery-map visualiser and the student
 * dashboard; the frontend joins nothing (F-034 exists precisely to retire the
 * client-side tree + state join).
 *
 * <p>Honesty rules: unpractised nodes carry {@code null} proficiency fields —
 * never zeros, never a fabricated band; misconception annotations appear only
 * on MISCONCEPTION nodes; {@code reviewDueAt} is the EARLIEST pending review
 * for that node (the complete queue stays on {@code GET /learners/me/state});
 * {@code asOf} pins the evaluation instant because effective mastery decays
 * with time.</p>
 */
public record LearnerKnowledgeGraphView(
        UUID learnerId,
        UUID rootId,
        String rootCode,
        String rootTitle,
        Instant asOf,
        List<NodeWithStateView> nodes,
        List<PrerequisiteEdgeView> prerequisiteEdges) {

    /**
     * Flat KG node projection plus learner annotations. Depth-first order,
     * children before siblings; {@code childIds} preserves the tree order so
     * clients can rebuild nesting without a second call. {@code type} is the
     * KG node type (SUBJECT / UNIT / TOPIC / SUBTOPIC / MISCONCEPTION).
     *
     * <p>{@code applicability} (T-C28) is the node's official paper/unit/tier
     * scope, passed through VERBATIM from the KG tree the service walks
     * (T-C24, V39) — present on spec-point nodes, {@code null} on every other
     * node. It is curriculum metadata, not learner state: clients render it
     * or omit it, never derive it.</p>
     */
    public record NodeWithStateView(
            UUID id, String code, String type, String title, String description,
            List<UUID> childIds,
            Double mastery, Double effectiveMastery, String band,
            Integer attempts, Integer correctCount, Instant lastPracticedAt,
            Double proceduralFluencyGap,
            Instant reviewDueAt, String reviewReason,
            Double misconceptionProbability, Boolean misconceptionActive,
            Map<String, Object> applicability) {
    }

    /** A drawable prerequisite edge: {@code prerequisiteId} → {@code nodeId} (which requires it). */
    public record PrerequisiteEdgeView(
            UUID prerequisiteId, String prerequisiteCode,
            UUID nodeId, String nodeCode) {
    }
}

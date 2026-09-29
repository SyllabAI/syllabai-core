package com.syllabai.classroom.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire views for the F-072 class knowledge-graph heatmap: the curriculum
 * subtree joined with class-level learner aggregation (§13.3) and the
 * TFA-03 teaching-coverage overlay (§13.4) — the taught/not-taught ×
 * understanding matrix, served in one read model so the hub never has to
 * fuse it client-side.
 *
 * <p>Honesty rules carried from the student KG and the coverage contract:
 * unmeasured nodes carry {@code learnersMeasured = 0}, a null mean and the
 * UNMEASURED band — never zeros, never a fabricated band; {@code
 * coverageState} exists exactly where the evidence justifies it (spec
 * points verbatim from their recorded row, non-spec nodes derived from
 * descendant rows) — "unrecorded" is the honest absence state, and grey on
 * the graph means ABSENT TEACHING COVERAGE, never low understanding.</p>
 */
public final class ClassKnowledgeGraphViews {

    private ClassKnowledgeGraphViews() {
    }

    /**
     * The class KG heatmap payload. {@code learnersEnrolled} counts the
     * ENABLED members whose evidence this aggregation consumed — the
     * independent-student rule lives here: a student without a membership
     * row in this class is not part of this number and not part of any
     * aggregate below, no matter what their learner model says.
     */
    public record ClassKnowledgeGraphView(
            UUID classId,
            String className,
            UUID rootId,
            String rootCode,
            String rootTitle,
            int learnersEnrolled,
            Instant asOf,
            List<ClassGraphNodeView> nodes,
            List<ClassGraphEdgeView> prerequisiteEdges) {
    }

    /**
     * One heatmap cell: a curriculum node × class evidence + coverage.
     *
     * <p>Aggregation (§13.3 — deliberately NOT a single average):
     * {@code learnersMeasured} students have a skill state on this node;
     * {@code meanMastery} is the mean of their EFFECTIVE (decayed, asOf)
     * mastery, null when nobody measured; {@code meanBand} uses the shared
     * learner-facing band vocabulary (LOW / DEVELOPING / SECURE /
     * UNMEASURED); the struggling/developing/proficient counts distribute
     * those measured students by band, so a polarized class cannot hide
     * behind the mean.</p>
     *
     * <p>Coverage (§13.4): {@code coverageState} is "taught" | "not-taught"
     * | "unrecorded". Spec points report their own recorded row verbatim;
     * every other node derives from its descendant spec points
     * ({@code taughtSpecPoints} > 0 → taught; else {@code recordedSpecPoints}
     * > 0 → not-taught; else unrecorded), with the raw counts exposed so the
     * client can show "2 of 5 points marked taught" without re-deriving
     * anything.</p>
     */
    public record ClassGraphNodeView(
            UUID id,
            String code,
            String type,
            String title,
            String description,
            List<UUID> childIds,
            String coverageState,
            int specPoints,
            int recordedSpecPoints,
            int taughtSpecPoints,
            int learnersMeasured,
            Double meanMastery,
            String meanBand,
            int strugglingCount,
            int developingCount,
            int proficientCount,
            int attempts,
            int correctCount,
            int learnersWithActiveMisconception) {
    }

    /** prerequisite edge over the subtree (same shape as the student KG) */
    public record ClassGraphEdgeView(
            UUID prerequisiteId,
            String prerequisiteCode,
            UUID nodeId,
            String nodeCode) {
    }
}

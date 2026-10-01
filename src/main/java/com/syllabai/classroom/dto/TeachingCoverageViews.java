package com.syllabai.classroom.dto;

import com.syllabai.classroom.TeachingCoverage;
import com.syllabai.classroom.TeachingCoverageEvent;
import com.syllabai.knowledge.KnowledgeNode;
import java.time.Instant;
import java.util.UUID;

/** Wire views for the teaching-coverage overlay (V52, TFA-03). */
public final class TeachingCoverageViews {

    private TeachingCoverageViews() {
    }

    /**
     * One recorded coverage row joined with its spec-point node identity —
     * the hub renders the checklist and the KG grey-state from this. Rows
     * exist only where a teacher asserted; absence of a row is the honest
     * "unrecorded" state the hub renders exactly like an explicit
     * NOT_TAUGHT (grey = absent teaching coverage).
     */
    public record CoverageRowView(
            UUID specPointNodeId,
            String code,
            String title,
            String status,
            UUID markedBy,
            Instant markedAt,
            String note,
            Instant firstMarkedAt) {

        public static CoverageRowView of(TeachingCoverage row, KnowledgeNode node) {
            return new CoverageRowView(
                    row.specPointNodeId(),
                    node != null ? node.code() : null,
                    node != null ? node.title() : null,
                    row.status().wire(),
                    row.markedBy(),
                    row.markedAt(),
                    row.note(),
                    row.createdAt());
        }
    }

    /** one append-only audit event, served verbatim (newest first upstream) */
    public record CoverageEventView(
            String status,
            String previousStatus,
            UUID actorId,
            String note,
            Instant createdAt) {

        public static CoverageEventView of(TeachingCoverageEvent event) {
            return new CoverageEventView(
                    event.status().wire(),
                    event.previousStatus() != null ? event.previousStatus().wire() : null,
                    event.actorId(),
                    event.note(),
                    event.createdAt());
        }
    }
}

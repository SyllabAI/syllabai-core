package com.syllabai.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The teacher's CURRENT teaching-coverage assertion for one specification
 * point on one class (V52, TFA-03; TEACHER_ARCHITECTURE §13.4 + §19).
 *
 * <p>This row is the overlay — it is NOT learner state and MUST never be read
 * or written by anything in the learner model (BKT, SkillState, misconception
 * evidence, review schedule): NOT_TAUGHT is not a mastery state, and a class
 * KG heatmap that needs to distinguish not-yet-taught curriculum from
 * taught-but-weak reads its separation from here.</p>
 *
 * <p>Only rows a teacher explicitly asserted exist — absence of a row is the
 * honest "unrecorded" state. Every status change (or note re-assertion) is
 * mirrored by an append-only {@link TeachingCoverageEvent}; this row carries
 * the latest state plus the first-assertion timestamp for provenance.
 * Composite-keyed per the {@link AnnouncementRead} house pattern.</p>
 */
@Entity
@Table(name = "teaching_coverage")
@IdClass(TeachingCoverage.Pk.class)
public class TeachingCoverage implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Status {
        TAUGHT, NOT_TAUGHT;

        /** tolerant parse for the hub's wire forms ("taught" / "not-taught") */
        public static Status parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase()) {
                case "taught" -> TAUGHT;
                case "not-taught", "not_taught" -> NOT_TAUGHT;
                default -> null;
            };
        }

        /** canonical wire form served to clients */
        public String wire() {
            return this == TAUGHT ? "taught" : "not-taught";
        }
    }

    /** composite primary key (class_id, spec_point_node_id) */
    public static class Pk implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID classId;
        private UUID specPointNodeId;

        public Pk() {
            // JPA
        }

        public Pk(UUID classId, UUID specPointNodeId) {
            this.classId = classId;
            this.specPointNodeId = specPointNodeId;
        }

        public UUID getClassId() { return classId; }
        public UUID getSpecPointNodeId() { return specPointNodeId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(classId, pk.classId)
                    && Objects.equals(specPointNodeId, pk.specPointNodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(classId, specPointNodeId);
        }
    }

    @Id
    @Column(name = "class_id")
    private UUID classId;

    @Id
    @Column(name = "spec_point_node_id")
    private UUID specPointNodeId;

    /**
     * Lifecycle-mapped per the SchoolClass house pattern
     * (EnumType.STRING over the VARCHAR(16) CHECK) — a Status argument can
     * never drift from the column form.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    /** the asserting teacher (ownership verified before any write) */
    @Column(name = "marked_by", nullable = false)
    private UUID markedBy;

    /** the latest assertion timestamp (§19 recorded_at) */
    @Column(name = "marked_at", nullable = false)
    private Instant markedAt;

    @Column(name = "note", length = 500)
    private String note;

    /** first-assertion timestamp — provenance on the state row itself */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TeachingCoverage() {
        // JPA
    }

    public TeachingCoverage(UUID classId, UUID specPointNodeId, Status status,
                            UUID markedBy, String note) {
        this.classId = classId;
        this.specPointNodeId = specPointNodeId;
        this.status = status;
        this.markedBy = markedBy;
        this.note = note;
        // ONE clock read: the first assertion timestamp IS the row's creation —
        // both provably the same moment (asserted by the flow IT)
        this.markedAt = Instant.now();
        this.createdAt = this.markedAt;
    }

    @PrePersist
    void onInsert() {
        if (createdAt == null) createdAt = Instant.now();
        if (markedAt == null) markedAt = Instant.now();
    }

    /** the one mutation this row allows: a teacher re-assertion (audited) */
    void reassert(Status status, UUID markedBy, String note) {
        this.status = status;
        this.markedBy = markedBy;
        this.note = note;
        this.markedAt = Instant.now();
    }

    public UUID classId() { return classId; }
    public UUID specPointNodeId() { return specPointNodeId; }
    public Status status() { return status; }
    public UUID markedBy() { return markedBy; }
    public Instant markedAt() { return markedAt; }
    public String note() { return note; }
    public Instant createdAt() { return createdAt; }
}

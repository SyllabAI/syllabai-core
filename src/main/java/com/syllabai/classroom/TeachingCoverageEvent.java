package com.syllabai.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One append-only teaching-coverage assertion event (V52, TFA-03) — the
 * §19 "provenance / audit metadata": every status change or note
 * re-assertion on a (class, spec-point) pair lands here verbatim with the
 * acting teacher, the transition ({@code previousStatus} NULL = first
 * assertion) and the timestamp, and is never updated or deleted. The
 * per-point history endpoint serves these rows as they were written;
 * the audit trail reconstructs, it does not summarize.
 */
@Entity
@Table(name = "teaching_coverage_events")
public class TeachingCoverageEvent {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "spec_point_node_id", nullable = false)
    private UUID specPointNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TeachingCoverage.Status status;

    /** NULL = first assertion on this point */
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 16)
    private TeachingCoverage.Status previousStatus;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TeachingCoverageEvent() {
        // JPA
    }

    public TeachingCoverageEvent(UUID classId, UUID specPointNodeId,
                                 TeachingCoverage.Status status,
                                 TeachingCoverage.Status previousStatus,
                                 UUID actorId, String note) {
        this.classId = classId;
        this.specPointNodeId = specPointNodeId;
        this.status = status;
        this.previousStatus = previousStatus;
        this.actorId = actorId;
        this.note = note;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID classId() { return classId; }
    public UUID specPointNodeId() { return specPointNodeId; }
    public TeachingCoverage.Status status() { return status; }
    public TeachingCoverage.Status previousStatus() { return previousStatus; }
    public UUID actorId() { return actorId; }
    public String note() { return note; }
    public Instant createdAt() { return createdAt; }
}

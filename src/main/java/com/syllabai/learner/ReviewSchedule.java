package com.syllabai.learner;

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
 * A scheduled review produced by the forgetting-curve job (Master Spec §11
 * "Review schedule", §24 table {@code review_schedules}).
 */
@Entity
@Table(name = "review_schedules")
public class ReviewSchedule {

    public enum Status { PENDING, COMPLETED, CANCELLED }
    public enum Reason { DECAY_CROSSED_THRESHOLD, TEACHER_ASSIGNED }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 40)
    private Reason reason = Reason.DECAY_CROSSED_THRESHOLD;

    /** mastery estimate that triggered the review, for research traceability */
    @Column(name = "mastery_at_trigger")
    private Double masteryAtTrigger;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReviewSchedule() {
        // JPA
    }

    public ReviewSchedule(UUID learnerId, UUID nodeId, Instant dueAt, Reason reason,
                          Double masteryAtTrigger) {
        this.learnerId = learnerId;
        this.nodeId = nodeId;
        this.dueAt = dueAt;
        this.reason = reason;
        this.masteryAtTrigger = masteryAtTrigger;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID learnerId() { return learnerId; }
    public UUID nodeId() { return nodeId; }
    public Instant dueAt() { return dueAt; }
    public Reason reason() { return reason; }
    public Double masteryAtTrigger() { return masteryAtTrigger; }
    public Status status() { return status; }
    public Instant createdAt() { return createdAt; }
}

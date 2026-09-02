package com.syllabai.learner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-learner, per-misconception probability maintained by BDT (Master Spec §11,
 * §24 suggested table {@code misconception_states}).
 */
@Entity
@Table(name = "misconception_states",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uq_misconception_state", columnNames = {"learner_id", "misconception_node_id"}))
public class MisconceptionState {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "misconception_node_id", nullable = false)
    private UUID misconceptionNodeId;

    /** current P(misconception held) */
    @Column(name = "probability", nullable = false)
    private double probability;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Column(name = "last_evidence_at", nullable = false)
    private Instant lastEvidenceAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MisconceptionState() {
        // JPA
    }

    public MisconceptionState(UUID learnerId, UUID misconceptionNodeId, double prior, Instant evidenceAt) {
        this.learnerId = learnerId;
        this.misconceptionNodeId = misconceptionNodeId;
        this.probability = prior;
        this.lastEvidenceAt = evidenceAt;
    }

    public void update(double newProbability, Instant evidenceAt) {
        this.probability = newProbability;
        this.evidenceCount++;
        this.lastEvidenceAt = evidenceAt;
    }

    @PrePersist
    void onInsert() {
        Instant now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID learnerId() { return learnerId; }
    public UUID misconceptionNodeId() { return misconceptionNodeId; }
    public double probability() { return probability; }
    public int evidenceCount() { return evidenceCount; }
    public Instant lastEvidenceAt() { return lastEvidenceAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}

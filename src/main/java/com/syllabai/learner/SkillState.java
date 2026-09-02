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
 * Per-learner, per-KG-node mastery state maintained by BKT (Master Spec §11,
 * §24 suggested table {@code skill_states}).
 */
@Entity
@Table(name = "skill_states",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uq_skill_state", columnNames = {"learner_id", "node_id"}))
public class SkillState {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    /** current BKT mastery estimate P(L) */
    @Column(name = "mastery", nullable = false)
    private double mastery;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "correct_count", nullable = false)
    private int correctCount;

    @Column(name = "last_practiced_at", nullable = false)
    private Instant lastPracticedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SkillState() {
        // JPA
    }

    public SkillState(UUID learnerId, UUID nodeId, double initialMastery, Instant practicedAt) {
        this.learnerId = learnerId;
        this.nodeId = nodeId;
        this.mastery = initialMastery;
        this.lastPracticedAt = practicedAt;
    }

    public void recordAttempt(boolean correct, double newMastery, Instant practicedAt) {
        this.mastery = newMastery;
        this.attempts++;
        if (correct) {
            this.correctCount++;
        }
        this.lastPracticedAt = practicedAt;
    }

    public void applyDecay(double decayedMastery, Instant when) {
        this.mastery = decayedMastery;
        this.updatedAt = when;
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
    public UUID nodeId() { return nodeId; }
    public double mastery() { return mastery; }
    public int attempts() { return attempts; }
    public int correctCount() { return correctCount; }
    public Instant lastPracticedAt() { return lastPracticedAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}

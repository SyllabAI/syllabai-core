package com.syllabai.teacher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * V22 durable audit of API-driven content-review mutations (the importer path
 * keeps its own hash-chained {@code teacher_validation_events}). Append-only:
 * written in the same transaction as the mutation, never updated or deleted.
 */
@Entity
@Table(name = "content_review_audit",
        indexes = {
                @Index(name = "ix_cra_target", columnList = "target_type, target_id"),
                @Index(name = "ix_cra_occurred", columnList = "occurred_at"),
                @Index(name = "ix_cra_actor", columnList = "actor_user_id")})
public class ContentReviewAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** the reviewer who acted — null only for system-context calls */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private com.syllabai.identity.User actor;

    /** denormalized actor email at decision time (readable history) */
    @Column(name = "actor_label", nullable = false, length = 254)
    private String actorLabel = "";

    @Column(name = "action", nullable = false, length = 24)
    private String action;

    @Column(name = "target_type", nullable = false, length = 24)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "from_state", length = 12)
    private String fromState;

    @Column(name = "to_state", length = 12)
    private String toState;

    @Column(name = "detail", nullable = false)
    private String detail = "";

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ContentReviewAudit() {
        // JPA
    }

    public ContentReviewAudit(com.syllabai.identity.User actor, String action,
                              String targetType, UUID targetId,
                              String fromState, String toState, String detail) {
        this.actor = actor;
        this.actorLabel = actor == null ? "system" : actor.email();
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.fromState = fromState;
        this.toState = toState;
        this.detail = detail == null ? "" : detail;
    }

    @PrePersist
    void onInsert() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public Long id() {
        return id;
    }

    public String actorLabel() {
        return actorLabel;
    }

    public String action() {
        return action;
    }

    public String targetType() {
        return targetType;
    }

    public UUID targetId() {
        return targetId;
    }

    public String fromState() {
        return fromState;
    }

    public String toState() {
        return toState;
    }

    public String detail() {
        return detail;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}

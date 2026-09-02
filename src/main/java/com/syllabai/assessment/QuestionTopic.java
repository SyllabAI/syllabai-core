package com.syllabai.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * First-class multi-topic mapping (Master Spec §10: "The system must not force a
 * multi-topic question into one topic"). {@code primary} marks the main tested node.
 */
@Entity
@Table(name = "question_topics",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uq_question_topic", columnNames = {"question_id", "node_id"}))
public class QuestionTopic {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QuestionTopic() {
        // JPA
    }

    public QuestionTopic(Question question, UUID nodeId, boolean primary) {
        this.question = question;
        this.nodeId = nodeId;
        this.primary = primary;
    }

    @PrePersist
    void onInsert() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID id() { return id; }
    public Question question() { return question; }
    public UUID questionId() { return question.id(); }
    public UUID nodeId() { return nodeId; }
    public boolean primary() { return primary; }
}

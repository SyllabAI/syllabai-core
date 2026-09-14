package com.syllabai.learner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One matched curriculum topic from one Tutor ask (V21 — the minimum viable
 * learner-memory pipeline from Tutor chats, P7). Written by
 * {@link TutorEngagementRecorder} from {@code TutorAnsweredEvent}: the topic
 * comes from the DETERMINISTIC intent matcher (never the LLM), and the row
 * carries the grounding strength and answering-model identity as provenance.
 *
 * <p>The raw question text is deliberately absent: it stays in the immutable
 * research telemetry log. This table is learner-memory signal — "the learner
 * asked about topic X at time T, grounded by N citations" — consumed by the
 * learner-state view and the NBA engine (T7a: asked-but-not-yet-practiced).</p>
 */
@Entity
@Table(name = "tutor_topic_engagements")
public class TutorTopicEngagement {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    /** matched KG topic — deterministic matcher output, the provenance backbone */
    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** evidence items grounding the answer (0 on deterministic refusal) */
    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    /** the ask's refusal flag — weak-match asks that the generator declined keep their topic rows */
    @Column(name = "refused", nullable = false)
    private boolean refused;

    /** answering model identity (null on deterministic refusal) */
    @Column(name = "answer_model", length = 120)
    private String answerModel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TutorTopicEngagement() {
        // JPA
    }

    public TutorTopicEngagement(UUID learnerId, UUID nodeId, Instant occurredAt,
                                int evidenceCount, boolean refused, String answerModel) {
        this.learnerId = learnerId;
        this.nodeId = nodeId;
        this.occurredAt = occurredAt;
        this.evidenceCount = evidenceCount;
        this.refused = refused;
        this.answerModel = answerModel;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID learnerId() { return learnerId; }
    public UUID nodeId() { return nodeId; }
    public Instant occurredAt() { return occurredAt; }
    public int evidenceCount() { return evidenceCount; }
    public boolean refused() { return refused; }
    public String answerModel() { return answerModel; }
    public Instant createdAt() { return createdAt; }
}

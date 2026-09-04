package com.syllabai.diagnostic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Versioned, evidence-backed struggle inference (Master Spec §17). */
@Entity
@Table(name = "struggle_inferences")
public class StruggleInference {

    @Id
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "topic_node_id", nullable = false)
    private UUID topicNodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private StruggleType type;

    @Column(name = "subtype", nullable = false, length = 80)
    private String subtype;

    @Column(name = "probability", nullable = false)
    private double probability;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_evidence", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> supportingEvidence;

    @Column(name = "model_version", nullable = false, length = 80)
    private String modelVersion;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "teacher_override", length = 80)
    private String teacherOverride;

    @Column(name = "overridden_by")
    private UUID overriddenBy;

    @Column(name = "overridden_at")
    private Instant overriddenAt;

    protected StruggleInference() {
        // JPA
    }

    public StruggleInference(UUID learnerId, UUID topicNodeId, StruggleType type,
                             String subtype, double probability,
                             Map<String, Object> supportingEvidence,
                             String modelVersion, Instant generatedAt, Instant expiresAt) {
        this.learnerId = learnerId;
        this.topicNodeId = topicNodeId;
        this.type = type;
        this.subtype = subtype;
        this.probability = probability;
        this.supportingEvidence = Map.copyOf(supportingEvidence);
        this.modelVersion = modelVersion;
        this.generatedAt = generatedAt;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID id() { return id; }
    public UUID learnerId() { return learnerId; }
    public UUID topicNodeId() { return topicNodeId; }
    public StruggleType type() { return type; }
    public String subtype() { return subtype; }
    public double probability() { return probability; }
    public Map<String, Object> supportingEvidence() { return supportingEvidence; }
    public String modelVersion() { return modelVersion; }
    public Instant generatedAt() { return generatedAt; }
    public Instant expiresAt() { return expiresAt; }
    public String teacherOverride() { return teacherOverride; }
    public UUID overriddenBy() { return overriddenBy; }
    public Instant overriddenAt() { return overriddenAt; }
}

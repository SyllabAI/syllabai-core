package com.syllabai.diagnostic;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
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

    /** non-null once a newer inference of the same (learner, topic, type) replaced this one; row is kept for research history */
    @Column(name = "superseded_at")
    private Instant supersededAt;

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
    public Instant supersededAt() { return supersededAt; }

    /**
     * Marks this inference as replaced by a newer one of the same
     * (learner, topic, type). The row is never deleted — research history
     * stays append-only — but read paths must exclude superseded rows.
     */
    void supersede(Instant at) {
        if (this.supersededAt == null) {
            this.supersededAt = at;
        }
    }

    /**
     * Records a teacher decision on this inference (Master Spec §17 override
     * support). Accepted decisions: {@code CONFIRMED} (read path treats the
     * inference as meeting the intervention threshold) and {@code REJECTED}
     * (read path excludes it). The teacher write surface is T-029; honoring
     * the decision on reads is implemented in {@code TutorPolicyService}.
     */
    public void applyTeacherOverride(String decision, UUID teacherId, Instant at) {
        this.teacherOverride = decision == null ? null : decision.strip().toUpperCase(Locale.ROOT);
        this.overriddenBy = teacherId;
        this.overriddenAt = at;
    }

    /** true when a teacher explicitly rejected this inference */
    public boolean teacherRejected() {
        return "REJECTED".equals(teacherOverride);
    }

    /** true when a teacher confirmed this inference */
    public boolean teacherConfirmed() {
        return "CONFIRMED".equals(teacherOverride);
    }
}

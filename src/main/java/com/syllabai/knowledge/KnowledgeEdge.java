package com.syllabai.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A directed edge in the knowledge graph (Master Spec §7).
 *
 * <p>Semantics by {@link RelationType}: PART_OF (child→parent), REQUIRES_PREREQUISITE
 * (node→its prerequisite), MISCONCEPTION_OF (misconception→topic), etc. Every edge
 * carries provenance, rationale, validation status and version so algorithm-suggested
 * relationships stay distinguishable from SME-validated ones (§7 requirement).</p>
 */
@Entity
@Table(name = "knowledge_edges",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uq_edge", columnNames = {"source_node_id", "target_node_id", "relation_type"}))
public class KnowledgeEdge {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_node_id", nullable = false)
    private KnowledgeNode source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_node_id", nullable = false)
    private KnowledgeNode target;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 30)
    private RelationType relationType;

    /** confidence/strength where applicable (0..1), null when not meaningful */
    @Column(name = "strength")
    private Double strength;

    @Column(name = "rationale", length = 500)
    private String rationale;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 20)
    private KnowledgeNode.ValidationStatus validationStatus = KnowledgeNode.ValidationStatus.UNVALIDATED;

    @Column(name = "provenance", length = 300)
    private String provenance;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KnowledgeEdge() {
        // JPA
    }

    public KnowledgeEdge(KnowledgeNode source, KnowledgeNode target, RelationType relationType,
                         Double strength, String rationale,
                         KnowledgeNode.ValidationStatus validationStatus,
                         String provenance, String createdBy) {
        this.source = source;
        this.target = target;
        this.relationType = relationType;
        this.strength = strength;
        this.rationale = rationale;
        this.validationStatus = validationStatus;
        this.provenance = provenance;
        this.createdBy = createdBy;
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
    public KnowledgeNode source() { return source; }
    public KnowledgeNode target() { return target; }
    public UUID sourceId() { return source.id(); }
    public UUID targetId() { return target.id(); }
    public RelationType relationType() { return relationType; }
    public Double strength() { return strength; }
    public String rationale() { return rationale; }
    public KnowledgeNode.ValidationStatus validationStatus() { return validationStatus; }
    public String provenance() { return provenance; }
    public String createdBy() { return createdBy; }
    public int version() { return version; }
    public Instant createdAt() { return createdAt; }

    /** §7 review workflow transitions (SUGGESTED → VALIDATED / back to UNVALIDATED). */
    public void validate() { this.validationStatus = KnowledgeNode.ValidationStatus.VALIDATED; }

    public void markUnvalidated() { this.validationStatus = KnowledgeNode.ValidationStatus.UNVALIDATED; }
}

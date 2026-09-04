package com.syllabai.knowledge;

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
 * A node in the syllabus knowledge graph (Master Spec §7).
 *
 * <p>Node identity and semantics live here; structure (parent/child, prerequisites,
 * misconception links) is carried entirely by {@link KnowledgeEdge} rows, so the graph
 * is queryable without hard-coded hierarchy columns. Provenance fields distinguish
 * SME-validated nodes from algorithm-suggested ones (§7 requirement).</p>
 */
@Entity
@Table(name = "knowledge_nodes")
public class KnowledgeNode {

    public enum ValidationStatus { UNVALIDATED, SUGGESTED, VALIDATED }

    @Id
    @Column(name = "id")
    private UUID id;

    /** stable human code, e.g. WCH11-T3.1 */
    @Column(name = "code", nullable = false, length = 40, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false, length = 20)
    private NodeType nodeType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 20)
    private ValidationStatus validationStatus = ValidationStatus.UNVALIDATED;

    /** provenance: where this node came from (spec page, examiner report, LLM draft) */
    @Column(name = "provenance", length = 300)
    private String provenance;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KnowledgeNode() {
        // JPA
    }

    public KnowledgeNode(String code, NodeType nodeType, String title, String description,
                         ValidationStatus validationStatus, String provenance, String createdBy) {
        this.code = code;
        this.nodeType = nodeType;
        this.title = title;
        this.description = description;
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
    public String code() { return code; }
    public NodeType nodeType() { return nodeType; }
    public String title() { return title; }
    public String description() { return description; }
    public ValidationStatus validationStatus() { return validationStatus; }
    public String provenance() { return provenance; }
    public String createdBy() { return createdBy; }
    public int version() { return version; }
    public Instant createdAt() { return createdAt; }

    /** §7 review workflow transitions (SUGGESTED → VALIDATED / back to UNVALIDATED). */
    public void validate() { this.validationStatus = ValidationStatus.VALIDATED; }

    public void markUnvalidated() { this.validationStatus = ValidationStatus.UNVALIDATED; }
}

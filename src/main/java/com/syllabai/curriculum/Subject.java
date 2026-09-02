package com.syllabai.curriculum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A subject inside a curriculum version (Master Spec §6.2, §7 hierarchy).
 * The subject's knowledge-graph root node is linked by {@code knowledgeNodeId} —
 * the KG is the canonical structure, this table carries curriculum metadata.
 */
@Entity
@Table(name = "subjects")
public class Subject {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @JoinColumn(name = "curriculum_version_id", nullable = false)
    private CurriculumVersion curriculumVersion;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** root knowledge-graph node (node_type = SUBJECT) */
    @Column(name = "knowledge_node_id")
    private UUID knowledgeNodeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Subject() {
        // JPA
    }

    public Subject(CurriculumVersion curriculumVersion, String code, String name) {
        this.curriculumVersion = curriculumVersion;
        this.code = code;
        this.name = name;
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

    public void linkKnowledgeNode(UUID knowledgeNodeId) {
        this.knowledgeNodeId = knowledgeNodeId;
    }

    public UUID id() { return id; }
    public CurriculumVersion curriculumVersion() { return curriculumVersion; }
    public String code() { return code; }
    public String name() { return name; }
    public UUID knowledgeNodeId() { return knowledgeNodeId; }
    public Instant createdAt() { return createdAt; }
}

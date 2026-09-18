package com.syllabai.sme;

import com.syllabai.assessment.Question;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * A question → specification-point mapping (T-C18 ratified design, landed by
 * ADR-026/V30). Spec points are knowledge nodes (SUBTOPIC-typed, codes like
 * {@code 4CH1-1.15}); the mapping carries a PRIMARY/SECONDARY role and the
 * two-tier AI/HUMAN validation vocabulary — SME-resolution mappings land
 * AI_VALIDATED under the operator's 2026-09-17 delegation and are upgradable
 * to HUMAN_VALIDATED by later review without re-import.
 */
@Entity
@Table(name = "question_spec_points",
        uniqueConstraints = @UniqueConstraint(name = "uq_question_spec_point",
                columnNames = {"question_id", "spec_point_node_id"}),
        indexes = {
            @Index(name = "ix_qsp_question", columnList = "question_id"),
            @Index(name = "ix_qsp_node", columnList = "spec_point_node_id")})
public class QuestionSpecPoint {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "spec_point_node_id", nullable = false)
    private UUID specPointNodeId;

    /** PRIMARY or SECONDARY */
    @Column(name = "role", nullable = false, length = 10)
    private String role;

    @Column(name = "provenance", nullable = false, length = 40)
    private String provenance = "AI_VALIDATED";

    @Column(name = "validation_state", nullable = false, length = 16)
    private String validationState = "AI_VALIDATED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected QuestionSpecPoint() {
    }

    public QuestionSpecPoint(Question question, UUID specPointNodeId, String role,
            String provenance) {
        this.question = question;
        this.specPointNodeId = specPointNodeId;
        this.role = role;
        this.provenance = provenance == null ? "AI_VALIDATED" : provenance;
        this.validationState = "AI_VALIDATED";
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

    public UUID id() {
        return id;
    }

    public UUID specPointNodeId() {
        return specPointNodeId;
    }

    public String role() {
        return role;
    }

    public String provenance() {
        return provenance;
    }

    public String validationState() {
        return validationState;
    }
}

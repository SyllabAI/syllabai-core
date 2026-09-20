package com.syllabai.assessment;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Mark-scheme decomposition for one question version (Master Spec §15): the scheme
 * is broken into {@link MarkPoint}s that the Smart Mark pipeline aligns learner
 * evidence against for partial credit. Extracted schemes start SUGGESTED; a teacher
 * validates — including authoring acceptance criteria where the extractor left them
 * empty (deterministic criteria are never invented by the pipeline).
 */
@Entity
@Table(name = "mark_schemes")
public class MarkScheme {

    public enum ValidationState { SUGGESTED, VALIDATED, REJECTED, FLAGGED }

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_version_id", nullable = false)
    private QuestionVersion questionVersion;

    /** read-only mirror of question_version_id — detached views read the FK without a proxy */
    @Column(name = "question_version_id", insertable = false, updatable = false)
    private UUID questionVersionIdColumn;

    @Column(name = "version_label", nullable = false, length = 20)
    private String versionLabel = "1";

    @Column(name = "source_document_id", length = 80)
    private String sourceDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_state", nullable = false, length = 12)
    private ValidationState validationState = ValidationState.SUGGESTED;

    @Column(name = "extraction_method", length = 120)
    private String extractionMethod;

    /**
     * Scheme-level general instructions from the board's mark scheme (V34, gap G-3):
     * "accept ecf", "ignore significant figure penalties", "allow reverse ordering".
     * These govern EVERY mark point decision — the marking prompt renders them as
     * their own section when present (prompt registry v2). Homeless before V34:
     * folding them into individual points lost their scheme-wide scope.
     */
    @Column(name = "general_guidance", columnDefinition = "text")
    private String generalGuidance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "markScheme", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordering")
    private List<MarkPoint> points = new ArrayList<>();

    protected MarkScheme() {
        // JPA
    }

    public MarkScheme(QuestionVersion questionVersion, String versionLabel,
                      String sourceDocumentId, String extractionMethod) {
        this.questionVersion = questionVersion;
        this.versionLabel = versionLabel;
        this.sourceDocumentId = sourceDocumentId;
        this.extractionMethod = extractionMethod;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public void addPoint(MarkPoint point) {
        points.add(point);
    }

    public UUID id() { return id; }
    public UUID questionVersionId() {
        // prefer the read-only FK mirror: safe on detached instances
        return questionVersionIdColumn != null ? questionVersionIdColumn
                : (questionVersion == null ? null : questionVersion.id());
    }
    public QuestionVersion questionVersion() { return questionVersion; }
    public String versionLabel() { return versionLabel; }
    public String sourceDocumentId() { return sourceDocumentId; }
    public ValidationState validationState() { return validationState; }
    public String extractionMethod() { return extractionMethod; }
    public String generalGuidance() { return generalGuidance; }
    public Instant createdAt() { return createdAt; }
    public List<MarkPoint> points() { return List.copyOf(points); }

    /** teacher-authored or bridge-extracted scheme-level instructions (V34, G-3) */
    public void setGeneralGuidance(String generalGuidance) {
        this.generalGuidance = generalGuidance;
    }

    public int totalMarks() {
        return points.stream().mapToInt(MarkPoint::marks).sum();
    }

    public void validate() { this.validationState = ValidationState.VALIDATED; }
    public void reject() { this.validationState = ValidationState.REJECTED; }

    /** V20: flag from SUGGESTED or VALIDATED — a flagged scheme never backs marking. */
    public void flag() {
        if (validationState != ValidationState.SUGGESTED && validationState != ValidationState.VALIDATED) {
            throw new IllegalStateException("mark scheme in state " + validationState + " cannot be flagged");
        }
        this.validationState = ValidationState.FLAGGED;
    }

    /** V20: unflag returns to SUGGESTED — re-validation required. */
    public void unflag() {
        if (validationState != ValidationState.FLAGGED) {
            throw new IllegalStateException("mark scheme in state " + validationState + " is not flagged");
        }
        this.validationState = ValidationState.SUGGESTED;
    }
}

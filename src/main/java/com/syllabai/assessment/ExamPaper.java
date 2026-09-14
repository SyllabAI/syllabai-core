package com.syllabai.assessment;

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
 * An exam paper such as "Edexcel IGCSE Chemistry 4CH0/1C January 2012" (Master Spec §6.5).
 * Papers are ingested from the canonical-document pipeline (syllabai-parser) and start
 * life {@link ValidationState#SUGGESTED}: nothing under a paper serves to learners until
 * a teacher validates the paper, its question versions and their mark schemes (§7).
 *
 * <p>Carries the canonical document ids of the source question paper and mark scheme so
 * every piece of derived content keeps its provenance chain (§8 checksums, §17).</p>
 */
@Entity
@Table(name = "exam_papers")
public class ExamPaper {

    public enum ValidationState { SUGGESTED, VALIDATED, REJECTED }
    public enum Provenance { PAST_PAPER, TEACHER_AUTHORED, SEED_DEMO }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "board", nullable = false, length = 40)
    private String board;

    @Column(name = "qualification", nullable = false, length = 20)
    private String qualification;

    @Column(name = "unit", length = 60)
    private String unit;

    @Column(name = "session_label", length = 60)
    private String sessionLabel;

    @Column(name = "paper_code", length = 30)
    private String paperCode;

    @Column(name = "question_paper_document_id", length = 80)
    private String questionPaperDocumentId;

    @Column(name = "mark_scheme_document_id", length = 80)
    private String markSchemeDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_state", nullable = false, length = 12)
    private ValidationState validationState = ValidationState.SUGGESTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance", nullable = false, length = 20)
    private Provenance provenance = Provenance.PAST_PAPER;

    @Column(name = "extraction_method", length = 120)
    private String extractionMethod;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExamPaper() {
        // JPA
    }

    public ExamPaper(UUID subjectId, String title, String board, String qualification,
                     String unit, String sessionLabel, String paperCode,
                     String questionPaperDocumentId, String markSchemeDocumentId,
                     Provenance provenance, String extractionMethod, UUID createdBy) {
        this.subjectId = subjectId;
        this.title = title;
        this.board = board;
        this.qualification = qualification;
        this.unit = unit;
        this.sessionLabel = sessionLabel;
        this.paperCode = paperCode;
        this.questionPaperDocumentId = questionPaperDocumentId;
        this.markSchemeDocumentId = markSchemeDocumentId;
        this.provenance = provenance;
        this.extractionMethod = extractionMethod;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID subjectId() { return subjectId; }

    /**
     * §7 content-review placement: move the paper into a real curriculum subject.
     * The ingestion pipeline never guesses curriculum placement — imported papers
     * wait in a neutral placeholder subject until a reviewer places them. This is
     * a factual association update ONLY: validation states and the serving
     * boundary are untouched.
     */
    public void assignSubject(UUID newSubjectId) {
        this.subjectId = newSubjectId;
    }
    public String title() { return title; }
    public String board() { return board; }
    public String qualification() { return qualification; }
    public String unit() { return unit; }
    public String sessionLabel() { return sessionLabel; }
    public String paperCode() { return paperCode; }
    public String questionPaperDocumentId() { return questionPaperDocumentId; }
    public String markSchemeDocumentId() { return markSchemeDocumentId; }
    public ValidationState validationState() { return validationState; }
    public Provenance provenance() { return provenance; }
    public String extractionMethod() { return extractionMethod; }
    public UUID createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }

    void setValidationState(ValidationState state) { this.validationState = state; }

    public void validate() { setValidationState(ValidationState.VALIDATED); }
    public void reject() { setValidationState(ValidationState.REJECTED); }
}

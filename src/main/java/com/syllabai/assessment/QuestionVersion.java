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
 * Immutable content snapshot of a question (Master Spec §10). Content edits create a
 * new version row — versions are never mutated — while the parent {@link Question}
 * keeps a denormalized "current" projection for the MCQ serving path.
 *
 * <p>Versioning + validation lifecycle: ingestion inserts v1 as
 * {@link ValidationState#SUGGESTED}; a teacher validates (or rejects) it. Only
 * questions whose current version is VALIDATED (or SEED_DEMO MCQs validated at
 * backfill) serve to learners.</p>
 */
@Entity
@Table(name = "question_versions")
public class QuestionVersion {

    public enum ValidationState { SUGGESTED, VALIDATED, REJECTED }

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    /** read-only mirror of question_id — lets detached views read the FK without a proxy */
    @Column(name = "question_id", insertable = false, updatable = false)
    private UUID questionIdColumn;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "stem", nullable = false, columnDefinition = "text")
    private String stem;

    @Column(name = "marks", nullable = false)
    private int marks;

    @Column(name = "difficulty", nullable = false)
    private int difficulty;

    @Column(name = "expected_time_seconds", nullable = false)
    private int expectedTimeSeconds;

    @Column(name = "command_word", length = 30)
    private String commandWord;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_state", nullable = false, length = 12)
    private ValidationState validationState = ValidationState.SUGGESTED;

    /** canonical document the content was extracted from (provenance chain §17) */
    @Column(name = "source_document_id", length = 80)
    private String sourceDocumentId;

    @Column(name = "extraction_confidence")
    private Double extractionConfidence;

    @Column(name = "extraction_method", length = 120)
    private String extractionMethod;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "questionVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordering")
    private List<QuestionPart> parts = new ArrayList<>();

    protected QuestionVersion() {
        // JPA
    }

    public QuestionVersion(Question question, int version, String stem, int marks,
                           int difficulty, int expectedTimeSeconds, String commandWord,
                           ValidationState validationState, String sourceDocumentId,
                           Double extractionConfidence, String extractionMethod) {
        this.question = question;
        this.version = version;
        this.stem = stem;
        this.marks = marks;
        this.difficulty = difficulty;
        this.expectedTimeSeconds = expectedTimeSeconds;
        this.commandWord = commandWord;
        this.validationState = validationState;
        this.sourceDocumentId = sourceDocumentId;
        this.extractionConfidence = extractionConfidence;
        this.extractionMethod = extractionMethod;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    /** register an extracted part (ingestion path); keeps ordering dense */
    public void addPart(QuestionPart part) {
        parts.add(part);
    }

    public UUID id() { return id; }
    public UUID questionId() {
        // prefer the read-only FK mirror: safe on detached instances (the association
        // field holds an uninitializable proxy after the session closes)
        return questionIdColumn != null ? questionIdColumn
                : (question == null ? null : question.id());
    }
    public Question question() { return question; }
    public int version() { return version; }
    public String stem() { return stem; }
    public int marks() { return marks; }
    public int difficulty() { return difficulty; }
    public int expectedTimeSeconds() { return expectedTimeSeconds; }
    public String commandWord() { return commandWord; }
    public ValidationState validationState() { return validationState; }
    public String sourceDocumentId() { return sourceDocumentId; }
    public Double extractionConfidence() { return extractionConfidence; }
    public String extractionMethod() { return extractionMethod; }
    public Instant createdAt() { return createdAt; }
    public List<QuestionPart> parts() { return List.copyOf(parts); }

    public void validate() { this.validationState = ValidationState.VALIDATED; }
    public void reject() { this.validationState = ValidationState.REJECTED; }
}

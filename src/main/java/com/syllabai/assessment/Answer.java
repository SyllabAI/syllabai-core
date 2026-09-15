package com.syllabai.assessment;

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
 * A learner's written answer to one {@link QuestionPart} of an {@link Attempt}.
 *
 * <p>Marking lifecycle (Master Spec §15): PENDING → SMART_MARKED (provisional, never
 * final truth) → HUMAN_MARKED (authoritative). A later human mark on an already-marked
 * answer becomes an OVERRIDE: it revises the marks and feeds the κ agreement gate, but
 * BKT evidence for the attempt has already fired once and is never re-fired.</p>
 */
@Entity
@Table(name = "answers")
public class Answer {

    public enum MarkingState { PENDING, SMART_MARKED, HUMAN_MARKED, OVERRIDDEN }

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false)
    private Attempt attempt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_part_id", nullable = false)
    private QuestionPart questionPart;

    /**
     * Read-only scalar mapping of the association's FK column (the established
     * QuestionVersion.questionIdColumn pattern) — id-only readers must not
     * initialize the LAZY questionPart proxy (field-access entities: even id()
     * initializes; the CLA pipeline reads answers outside any transaction).
     */
    @Column(name = "question_part_id", insertable = false, updatable = false)
    private UUID questionPartIdColumn;

    @Column(name = "answer_text", columnDefinition = "text")
    private String answerText;

    @Column(name = "marks_awarded")
    private Integer marksAwarded;

    @Enumerated(EnumType.STRING)
    @Column(name = "marking_state", nullable = false, length = 16)
    private MarkingState markingState = MarkingState.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Answer() {
        // JPA
    }

    public Answer(Attempt attempt, QuestionPart questionPart, String answerText) {
        this.attempt = attempt;
        this.questionPart = questionPart;
        this.answerText = answerText;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID attemptId() { return attempt.id(); }
    public Attempt attempt() { return attempt; }
    public UUID questionPartId() {
        if (questionPartIdColumn != null) {
            return questionPartIdColumn;
        }
        return questionPart.id();
    }
    public QuestionPart questionPart() { return questionPart; }
    public String answerText() { return answerText; }
    public Integer marksAwarded() { return marksAwarded; }
    public MarkingState markingState() { return markingState; }
    public Instant createdAt() { return createdAt; }

    /** smart mark accepted: provisional marks, still overridable by a human */
    public void smartMarked(int marks) {
        this.marksAwarded = marks;
        this.markingState = MarkingState.SMART_MARKED;
    }

    /** human mark on a pending/smart-marked answer: authoritative */
    public void humanMarked(int marks) {
        this.marksAwarded = marks;
        this.markingState = MarkingState.HUMAN_MARKED;
    }

    /** human mark revising a finished human mark: override (research-visible) */
    public void overridden(int marks) {
        this.marksAwarded = marks;
        this.markingState = MarkingState.OVERRIDDEN;
    }
}

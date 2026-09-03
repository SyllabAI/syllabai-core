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
 * One learner attempt at one question. This table stores the raw evidence fields of
 * Paper B §3.5 that are available server-side; on submit, an immutable
 * {@code AssessmentEvidenceRecordedEvent} is published and the learner model reacts
 * (Master Spec §12 — assessment never mutates learner state directly).
 */
@Entity
@Table(name = "attempts")
public class Attempt {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "chosen_option_id")
    private UUID chosenOptionId;

    @Column(name = "correct", nullable = false)
    private boolean correct;

    @Column(name = "marks_awarded")
    private Integer marksAwarded;

    /** server-observed response time (ms) from question serve to submission */
    @Column(name = "response_time_ms", nullable = false)
    private long responseTimeMs;

    /** learner-reported confidence 1–5 (Paper B §3.5 confidence calibration) */
    @Column(name = "confidence_level")
    private Integer confidenceLevel;

    /** learner self-doubt flag — metacognitive struggle signal (Paper A type 4) */
    @Column(name = "self_doubt_flag", nullable = false)
    private boolean selfDoubtFlag;

    /** answered under timed conditions (Paper B §16 fluency-gap construct) */
    @Column(name = "timed_condition", nullable = false)
    private boolean timedCondition;

    /** marking lifecycle (V8): MCQ = AUTO_GRADED; STRUCTURED walks PENDING → …_MARKED */
    @Enumerated(EnumType.STRING)
    @Column(name = "marking_state", nullable = false, length = 16)
    private MarkingState markingState = MarkingState.AUTO_GRADED;

    /**
     * Single-fire guard for the evidence contract: MCQ attempts emit evidence at
     * submit (existing behaviour); structured attempts emit exactly once, at first
     * authoritative marking. A later human override revises marks for research but
     * must never re-run BKT/BDT on the same attempt.
     */
    @Column(name = "evidence_emitted", nullable = false)
    private boolean evidenceEmitted;

    @Column(name = "provenance", nullable = false, length = 40)
    private String provenance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public enum MarkingState { AUTO_GRADED, PENDING, SMART_MARKED, HUMAN_MARKED, OVERRIDDEN }

    protected Attempt() {
        // JPA
    }

    public Attempt(UUID learnerId, Question question, UUID chosenOptionId, boolean correct,
                   Integer marksAwarded, long responseTimeMs, Integer confidenceLevel,
                   boolean selfDoubtFlag, boolean timedCondition, String provenance) {
        this.learnerId = learnerId;
        this.question = question;
        this.chosenOptionId = chosenOptionId;
        this.correct = correct;
        this.marksAwarded = marksAwarded;
        this.responseTimeMs = responseTimeMs;
        this.confidenceLevel = confidenceLevel;
        this.selfDoubtFlag = selfDoubtFlag;
        this.timedCondition = timedCondition;
        this.provenance = provenance;
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
    public UUID learnerId() { return learnerId; }
    public Question question() { return question; }
    public UUID questionId() { return question.id(); }
    public UUID chosenOptionId() { return chosenOptionId; }
    public boolean correct() { return correct; }
    public Integer marksAwarded() { return marksAwarded; }
    public long responseTimeMs() { return responseTimeMs; }
    public Integer confidenceLevel() { return confidenceLevel; }
    public boolean selfDoubtFlag() { return selfDoubtFlag; }
    public boolean timedCondition() { return timedCondition; }
    public MarkingState markingState() { return markingState; }
    public boolean evidenceEmitted() { return evidenceEmitted; }
    public String provenance() { return provenance; }
    public Instant createdAt() { return createdAt; }

    public void beginMarking() {
        this.markingState = MarkingState.PENDING;
    }

    public void smartMarked() {
        this.markingState = MarkingState.SMART_MARKED;
    }

    public void humanMarked(boolean revising) {
        this.markingState = revising ? MarkingState.OVERRIDDEN : MarkingState.HUMAN_MARKED;
    }

    /**
     * Settle the whole-attempt mark total from its part answers and the stored
     * correctness flag. Structured attempts are created with a placeholder
     * {@code correct=false}; the first authoritative marking settles it with the
     * documented conservative rule (full marks = correct) so raw-column consumers
     * (fluency-gap aggregation, analytics) agree with the evidence event.
     */
    public void recordTotalMarks(int totalAwarded, int marksTotal) {
        this.marksAwarded = totalAwarded;
        this.correct = marksTotal > 0 && totalAwarded >= marksTotal;
    }

    /** idempotent: flips the guard, returns false when evidence already fired */
    public boolean markEvidenceEmitted() {
        if (evidenceEmitted) return false;
        this.evidenceEmitted = true;
        return true;
    }
}

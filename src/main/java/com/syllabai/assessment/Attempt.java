package com.syllabai.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "provenance", nullable = false, length = 40)
    private String provenance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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
    public String provenance() { return provenance; }
    public Instant createdAt() { return createdAt; }
}

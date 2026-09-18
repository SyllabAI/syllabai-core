package com.syllabai.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The learner's own authoritative self-assessment of one {@link Answer}
 * (ADR-026 SME practice tranche) — the Save-My-Exams-style "reveal the mark
 * scheme, tick what you earned" record.
 *
 * <p>Deliberately NOT a {@code HumanMark}: the κ agreement sample (F-161) pairs
 * Smart Mark runs with teacher human marks, so self-assessments live in their
 * own table and can never enter the κ pairing by construction. The settle and
 * once-only evidence mechanics are shared with the teacher path
 * ({@code TeacherMarkingService}); only the provenance differs.</p>
 */
@Entity
@Table(name = "learner_self_marks",
        indexes = {
                @Index(name = "ix_lsm_answer", columnList = "answer_id"),
                @Index(name = "ix_lsm_learner", columnList = "learner_id")})
public class LearnerSelfMark {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "answer_id", nullable = false)
    private Answer answer;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "marks_awarded", nullable = false)
    private int marksAwarded;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LearnerSelfMark() {
        // JPA
    }

    public LearnerSelfMark(Answer answer, UUID learnerId, int marksAwarded, String comment) {
        this.answer = answer;
        this.learnerId = learnerId;
        this.marksAwarded = marksAwarded;
        this.comment = comment;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public Answer answer() { return answer; }
    public UUID learnerId() { return learnerId; }
    public int marksAwarded() { return marksAwarded; }
    public String comment() { return comment; }
    public Instant createdAt() { return createdAt; }
}

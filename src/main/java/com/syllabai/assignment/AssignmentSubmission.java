package com.syllabai.assignment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One learner hand-in (V49): append-only completion evidence for an
 * {@link Assignment}. Every submission action is a row; the latest row per
 * {@code (assignment_id, learner_id)} is the current hand-in and a
 * re-hand-in (an improved score, a late catch-up) is NEW evidence rather
 * than an overwrite — the trail preserves the work history, exactly the
 * V47/V48 append-only discipline.
 *
 * <p>What this row deliberately is NOT:</p>
 * <ul>
 *   <li>NEVER mastery: a hand-in records completion state (how much of the
 *       work was done, an optional self-marked score). No BKT update, no
 *       {@code SkillState} row, no misconception evidence, no
 *       ReviewSchedule. Mastery comes from marked attempts only — the
 *       mastery evidence an assignment generates flows through the EXISTING
 *       attempt pipeline unchanged. Pinned by AssignmentFlowIT, which
 *       asserts SkillStateRepository stays empty after submission traffic.</li>
 *   <li>NEVER the attempt record: the score here is the hub's derived
 *       hand-in summary (self-marked work), not a per-question attempt —
 *       core's assessment tables remain the only source of attempt truth.</li>
 * </ul>
 *
 * <p>{@code questionsCompleted} is validated against the assignment's
 * {@code question_count} and {@code score} against its {@code marks_total}
 * at the boundary — a hand-in can never claim more work than the
 * assignment contains.</p>
 */
@Entity
@Table(name = "assignment_submissions")
public class AssignmentSubmission {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "questions_completed", nullable = false)
    private int questionsCompleted;

    /** optional self-marked score; null = handed in unmarked */
    @Column(name = "score")
    private Integer score;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AssignmentSubmission() {
        // JPA
    }

    public AssignmentSubmission(UUID assignmentId, UUID learnerId,
                                int questionsCompleted, Integer score,
                                Instant occurredAt) {
        this.assignmentId = assignmentId;
        this.learnerId = learnerId;
        this.questionsCompleted = questionsCompleted;
        this.score = score;
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID assignmentId() { return assignmentId; }
    public UUID learnerId() { return learnerId; }
    public int questionsCompleted() { return questionsCompleted; }
    public Integer score() { return score; }
    public Instant occurredAt() { return occurredAt; }
    public Instant createdAt() { return createdAt; }
}

package com.syllabai.smartmark;

import com.syllabai.assessment.Answer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A teacher's mark on an {@link Answer} — the authoritative grade (Master Spec §15:
 * LLM never final truth; human review/override is a first-class record). Per-mark-point
 * decisions are captured so Cohen's κ agreement with Smart Mark can be computed at
 * mark-point granularity (F-161, §15 calibration dataset).
 */
@Entity
@Table(name = "human_marks")
public class HumanMark {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_id", nullable = false)
    private Answer answer;

    @Column(name = "marker_id", nullable = false)
    private UUID markerId;

    @Column(name = "marks_awarded", nullable = false)
    private int marksAwarded;

    /** {markPointId: 0|1} per-point award decisions for κ agreement (nullable) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "per_point_decisions", columnDefinition = "jsonb")
    private Map<String, Integer> perPointDecisions;

    @Column(name = "comments", columnDefinition = "text")
    private String comments;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HumanMark() {
        // JPA
    }

    public HumanMark(Answer answer, UUID markerId, int marksAwarded,
                     Map<String, Integer> perPointDecisions, String comments) {
        this.answer = answer;
        this.markerId = markerId;
        this.marksAwarded = marksAwarded;
        this.perPointDecisions = perPointDecisions;
        this.comments = comments;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID answerId() { return answer.id(); }
    public UUID markerId() { return markerId; }
    public int marksAwarded() { return marksAwarded; }
    public Map<String, Integer> perPointDecisions() { return perPointDecisions; }
    public String comments() { return comments; }
    public Instant createdAt() { return createdAt; }
}

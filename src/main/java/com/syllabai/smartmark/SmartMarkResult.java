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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One Smart Mark run against one {@link Answer} (Master Spec §15). Append-only run log:
 * re-running the pipeline inserts a new row, never updates an old one — the calibration
 * dataset (Paper B) needs the full history of model decisions, including failures.
 *
 * <p>An accepted run is still <strong>not final truth</strong>: the answer becomes
 * SMART_MARKED (provisional) and the κ ≥ 0.60 agreement gate governs whether smart
 * marks may release to learners at all; humans always retain the override.</p>
 */
@Entity
@Table(name = "smart_mark_results")
public class SmartMarkResult {

    public static final String PIPELINE_VERSION = "1.0.0";

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_id", nullable = false)
    private Answer answer;

    @Column(name = "pipeline_version", nullable = false, length = 20)
    private String pipelineVersion = PIPELINE_VERSION;

    /** model that produced the candidate allocation (§19 reproducibility) */
    @Column(name = "model_id", length = 80)
    private String modelId;

    @Column(name = "marks_awarded", nullable = false)
    private int marksAwarded;

    /** pipeline self-reported confidence 0–1 (validated, not trusted blindly) */
    @Column(name = "confidence")
    private Double confidence;

    /** true only when every deterministic validator accepted the candidate */
    @Column(name = "validation_passed", nullable = false)
    private boolean validationPassed;

    /**
     * Per-mark-point decision: {markPointId, ref, marks, awarded(bool), evidence,
     * rationale} — the explainable part of §15.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "breakdown", columnDefinition = "jsonb")
    private List<Map<String, Object>> breakdown = new java.util.ArrayList<>();

    /** machine-readable failure reason when validation rejected the candidate */
    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    /** raw generator output retained verbatim for audit (never parsed on read) */
    @Column(name = "raw_output", columnDefinition = "text")
    private String rawOutput;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SmartMarkResult() {
        // JPA
    }

    public SmartMarkResult(Answer answer, String modelId, int marksAwarded,
                           Double confidence, boolean validationPassed,
                           List<Map<String, Object>> breakdown, String failureReason,
                           String rawOutput) {
        this.answer = answer;
        this.modelId = modelId;
        this.marksAwarded = marksAwarded;
        this.confidence = confidence;
        this.validationPassed = validationPassed;
        this.breakdown = breakdown == null ? new java.util.ArrayList<>() : breakdown;
        this.failureReason = failureReason;
        this.rawOutput = rawOutput;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID answerId() { return answer.id(); }
    public String pipelineVersion() { return pipelineVersion; }
    public String modelId() { return modelId; }
    public int marksAwarded() { return marksAwarded; }
    public Double confidence() { return confidence; }
    public boolean validationPassed() { return validationPassed; }
    public List<Map<String, Object>> breakdown() { return breakdown; }
    public String failureReason() { return failureReason; }
    public String rawOutput() { return rawOutput; }
    public Instant createdAt() { return createdAt; }
}

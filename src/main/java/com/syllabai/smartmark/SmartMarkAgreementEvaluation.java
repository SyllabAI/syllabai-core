package com.syllabai.smartmark;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One κ agreement-gate evaluation (F-161): Cohen's κ between Smart Mark and human
 * double-marking over the paired sample, with the release decision
 * (κ ≥ 0.60 releases Smart Mark to learners; the threshold is recorded per row —
 * it is a research parameter, not a hidden constant). Append-only record.
 */
@Entity
@Table(name = "smart_mark_agreement_evaluations",
       indexes = @Index(name = "ix_agreement_computed", columnList = "computed_at DESC"))
public class SmartMarkAgreementEvaluation {

    public static final double DEFAULT_THRESHOLD = 0.60;
    public static final String SCOPE_PAPER = "PAPER";
    public static final String SCOPE_ALL = "ALL";

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "scope", nullable = false, length = 12)
    private String scope;

    @Column(name = "exam_paper_id")
    private UUID examPaperId;

    /** paired mark-point decisions the κ was computed over */
    @Column(name = "sample_size", nullable = false)
    private int sampleSize;

    @Column(name = "kappa", nullable = false)
    private double kappa;

    /** raw proportion of agreement (κ context: high agreement + low κ = skewed marginals) */
    @Column(name = "observed_agreement", nullable = false)
    private double observedAgreement;

    @Column(name = "threshold", nullable = false)
    private double threshold;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "computed_by")
    private UUID computedBy;

    protected SmartMarkAgreementEvaluation() {
        // JPA
    }

    public SmartMarkAgreementEvaluation(String scope, UUID examPaperId, int sampleSize,
                                        double kappa, double observedAgreement,
                                        double threshold, UUID computedBy) {
        this.scope = scope;
        this.examPaperId = examPaperId;
        this.sampleSize = sampleSize;
        this.kappa = kappa;
        this.observedAgreement = observedAgreement;
        this.threshold = threshold;
        this.passed = kappa >= threshold;
        this.computedBy = computedBy;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (computedAt == null) computedAt = Instant.now();
    }

    public UUID id() { return id; }
    public String scope() { return scope; }
    public UUID examPaperId() { return examPaperId; }
    public int sampleSize() { return sampleSize; }
    public double kappa() { return kappa; }
    public double observedAgreement() { return observedAgreement; }
    public double threshold() { return threshold; }
    public boolean passed() { return passed; }
    public Instant computedAt() { return computedAt; }
    public UUID computedBy() { return computedBy; }
}

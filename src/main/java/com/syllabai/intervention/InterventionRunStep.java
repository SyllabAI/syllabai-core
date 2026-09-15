package com.syllabai.intervention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "intervention_run_step")
public class InterventionRunStep {

    @Id
    @Column(name = "step_id", nullable = false)
    private UUID stepId;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "observation_type", nullable = false, length = 64)
    private String observationType;

    @Column(name = "input_evidence_ref", columnDefinition = "text")
    private String inputEvidenceRef;

    @Column(name = "output_evidence_ref", columnDefinition = "text")
    private String outputEvidenceRef;

    @Column(name = "blocked_reason", columnDefinition = "text")
    private String blockedReason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected InterventionRunStep() { }

    public InterventionRunStep(UUID runId, int sequenceNo, String status,
                                String observationType, String inputEvidenceRef,
                                String outputEvidenceRef, String blockedReason,
                                Instant startedAt, Instant completedAt) {
        if (runId == null) throw new IllegalArgumentException("runId is required");
        if (sequenceNo < 0) throw new IllegalArgumentException("sequenceNo must be non-negative");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status is required");
        if (observationType == null || observationType.isBlank()) throw new IllegalArgumentException("observationType is required");
        this.runId = runId;
        this.sequenceNo = sequenceNo;
        this.status = status;
        this.observationType = observationType;
        this.inputEvidenceRef = inputEvidenceRef;
        this.outputEvidenceRef = outputEvidenceRef;
        this.blockedReason = blockedReason;
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
        this.completedAt = completedAt;
    }

    @PrePersist
    void onInsert() {
        if (stepId == null) stepId = UUID.randomUUID();
    }

    public UUID stepId() { return stepId; }
    public UUID runId() { return runId; }
    public int sequenceNo() { return sequenceNo; }
    public String status() { return status; }
    public String observationType() { return observationType; }
    public String inputEvidenceRef() { return inputEvidenceRef; }
    public String outputEvidenceRef() { return outputEvidenceRef; }
    public String blockedReason() { return blockedReason; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
}

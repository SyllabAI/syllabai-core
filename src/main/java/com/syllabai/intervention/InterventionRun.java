package com.syllabai.intervention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Bounded audit/execution record for one adaptive learning intervention.
 *
 * <p>This entity deliberately contains references/snapshots, not learner-state
 * mutation logic. Learner-model services remain the sole authority for state
 * transitions.</p>
 */
@Entity
@Table(name = "intervention_run")
public class InterventionRun {

    @Id
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "curriculum_version_id")
    private UUID curriculumVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InterventionRunStatus status;

    @Column(name = "origin", nullable = false, length = 64)
    private String origin;

    /**
     * jsonb columns carry pre-serialized JSON strings — SqlTypes.JSON makes
     * Hibernate send a json-typed parameter (a bare varchar parameter fails
     * against a real Postgres jsonb column; this was the live-verified 500 on
     * the first production write — the lane's unit tests were mock-based and
     * the IT had never executed).
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "target_specification_points", nullable = false, columnDefinition = "jsonb")
    private String targetSpecificationPoints = "[]";

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "question_part_ids", nullable = false, columnDefinition = "jsonb")
    private String questionPartIds = "[]";

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "evidence_refs", nullable = false, columnDefinition = "jsonb")
    private String evidenceRefs = "[]";

    @Column(name = "diagnosis_snapshot_ref")
    private String diagnosisSnapshotRef;

    @Column(name = "learner_state_snapshot_ref")
    private String learnerStateSnapshotRef;

    @Column(name = "diagnosis_version")
    private String diagnosisVersion;

    @Column(name = "action_type", nullable = false, length = 32)
    private String actionType;

    @Column(name = "intervention_version", nullable = false, length = 128)
    private String interventionVersion;

    @Column(name = "intervention_hash", nullable = false, length = 128)
    private String interventionHash;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "allowed_tool_ids", nullable = false, columnDefinition = "jsonb")
    private String allowedToolIds = "[]";

    @Column(name = "terminal_outcome", length = 64)
    private String terminalOutcome;

    @Column(name = "current_step")
    private Integer currentStep;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected InterventionRun() {
        // JPA
    }

    public InterventionRun(UUID learnerId, UUID subjectId, UUID curriculumVersionId,
                           String origin, String targetSpecificationPoints,
                           String questionPartIds, String evidenceRefs,
                           String diagnosisSnapshotRef, String learnerStateSnapshotRef,
                           String diagnosisVersion, String actionType,
                           String interventionVersion, String interventionHash,
                           String allowedToolIds) {
        this.learnerId = learnerId;
        this.subjectId = subjectId;
        this.curriculumVersionId = curriculumVersionId;
        this.origin = requireText(origin, "origin");
        this.targetSpecificationPoints = requireJson(targetSpecificationPoints);
        this.questionPartIds = requireJson(questionPartIds);
        this.evidenceRefs = requireJson(evidenceRefs);
        this.diagnosisSnapshotRef = diagnosisSnapshotRef;
        this.learnerStateSnapshotRef = learnerStateSnapshotRef;
        this.diagnosisVersion = diagnosisVersion;
        this.actionType = requireText(actionType, "actionType");
        this.interventionVersion = requireText(interventionVersion, "interventionVersion");
        this.interventionHash = requireText(interventionHash, "interventionHash");
        this.allowedToolIds = requireJson(allowedToolIds);
        this.status = InterventionRunStatus.CREATED;
    }

    @PrePersist
    void onInsert() {
        if (runId == null) runId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID runId() { return runId; }
    public UUID learnerId() { return learnerId; }
    public UUID subjectId() { return subjectId; }
    public UUID curriculumVersionId() { return curriculumVersionId; }
    public InterventionRunStatus status() { return status; }
    public String origin() { return origin; }
    public String targetSpecificationPoints() { return targetSpecificationPoints; }
    public String questionPartIds() { return questionPartIds; }
    public String evidenceRefs() { return evidenceRefs; }
    public String diagnosisSnapshotRef() { return diagnosisSnapshotRef; }
    public String learnerStateSnapshotRef() { return learnerStateSnapshotRef; }
    public String diagnosisVersion() { return diagnosisVersion; }
    public String actionType() { return actionType; }
    public String interventionVersion() { return interventionVersion; }
    public String interventionHash() { return interventionHash; }
    public String allowedToolIds() { return allowedToolIds; }
    public String terminalOutcome() { return terminalOutcome; }
    public Integer currentStep() { return currentStep; }
    public Instant createdAt() { return createdAt; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public Instant cancelledAt() { return cancelledAt; }

    void activate(Instant now) {
        requireNotTerminal();
        if (status != InterventionRunStatus.CREATED && status != InterventionRunStatus.PAUSED) {
            throw new IllegalStateException("Run cannot become ACTIVE from " + status);
        }
        status = InterventionRunStatus.ACTIVE;
        if (startedAt == null) startedAt = now;
    }

    void pause() {
        requireNotTerminal();
        if (status != InterventionRunStatus.ACTIVE) throw new IllegalStateException("Only ACTIVE runs can pause");
        status = InterventionRunStatus.PAUSED;
    }

    void recordStep(int sequence) {
        requireNotTerminal();
        if (status != InterventionRunStatus.ACTIVE) throw new IllegalStateException("Only ACTIVE runs can record steps");
        if (sequence < 0 || (currentStep != null && sequence <= currentStep)) {
            throw new IllegalArgumentException("Step sequence must increase monotonically");
        }
        currentStep = sequence;
    }

    void complete(String outcome, Instant now) {
        if (status.terminal()) throw new IllegalStateException("Terminal run cannot be changed");
        if (status != InterventionRunStatus.ACTIVE) throw new IllegalStateException("Only ACTIVE runs can complete");
        status = InterventionRunStatus.COMPLETED;
        terminalOutcome = requireText(outcome, "outcome");
        completedAt = now;
    }

    void cancel(Instant now) {
        if (status.terminal()) throw new IllegalStateException("Terminal run cannot be changed");
        status = InterventionRunStatus.CANCELLED;
        cancelledAt = now;
    }

    private void requireNotTerminal() {
        if (status.terminal()) throw new IllegalStateException("Terminal run cannot be changed");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static String requireJson(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("JSON field is required");
        return value;
    }
}

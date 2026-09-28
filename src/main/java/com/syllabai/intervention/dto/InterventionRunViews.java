package com.syllabai.intervention.dto;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Learner-facing InterventionRun views (E2 prototype). The run carries
 * REFERENCES and snapshots, never canonical evidence copies and never
 * learner-state values to act on; reconstruction is read-only.
 */
public final class InterventionRunViews {

    private InterventionRunViews() {
    }

    /** Full run reconstruction: identity + snapshot references + observations. */
    public record RunView(
            UUID runId,
            UUID learnerId,
            UUID subjectId,
            UUID curriculumVersionId,
            String status,
            String origin,
            List<String> targetSpecificationPoints,
            List<String> questionPartIds,
            List<String> evidenceRefs,
            String diagnosisSnapshotRef,
            String learnerStateSnapshotRef,
            String diagnosisVersion,
            String actionType,
            String interventionVersion,
            String interventionHash,
            List<String> allowedToolIds,
            String terminalOutcome,
            Integer currentStep,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant cancelledAt,
            List<StepView> steps,
            List<EvidenceView> evidence) {

        public record StepView(
                UUID stepId,
                int sequenceNo,
                String status,
                String observationType,
                String inputEvidenceRef,
                String outputEvidenceRef,
                String blockedReason,
                Instant startedAt,
                Instant completedAt) {
        }

        public record EvidenceView(
                UUID id,
                String evidenceRef,
                String role,
                Instant capturedAt) {
        }
    }

    /** Resume identity (contract §6): both fields are mandatory. */
    public record ResumeRequest(String interventionVersion, String interventionHash) {
    }

    /** One ordered step observation (server assigns the sequence number).
     *  Fields are size-capped (R13): this is a learner-authenticated DB write
     *  surface; refs are canonical-record references, not free prose. */
    public record StepRequest(
            @Size(max = 16) String status,
            @Size(max = 60) String observationType,
            @Size(max = 120) String inputEvidenceRef,
            @Size(max = 120) String outputEvidenceRef,
            @Size(max = 500) String blockedReason) {
    }

    /** An evidence REFERENCE attachment (the canonical record is not copied). */
    public record EvidenceRequest(
            @Size(max = 120) String evidenceRef,
            @Size(max = 40) String role) {
    }

    /** Terminal outcome for completion (e.g. EVIDENCE_COLLECTED). */
    public record CompleteRequest(@Size(max = 60) String terminalOutcome) {
    }
}

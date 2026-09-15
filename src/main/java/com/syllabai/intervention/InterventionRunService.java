package com.syllabai.intervention;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded application service for InterventionRun execution.
 *
 * <p>There is intentionally no LearnerModelService dependency here: recording
 * an intervention observation/evidence reference cannot itself mutate mastery.
 * The learner model consumes canonical evidence separately.</p>
 */
@Service
public class InterventionRunService {

    private final InterventionRunRepository runs;
    private final InterventionRunStepRepository steps;
    private final InterventionRunEvidenceRepository evidence;

    public InterventionRunService(InterventionRunRepository runs,
                                   InterventionRunStepRepository steps,
                                   InterventionRunEvidenceRepository evidence) {
        this.runs = runs;
        this.steps = steps;
        this.evidence = evidence;
    }

    @Transactional
    public InterventionRun create(CreateCommand command) {
        Objects.requireNonNull(command, "command");
        String hash = command.interventionHash() == null || command.interventionHash().isBlank()
                ? hashIntervention(command.interventionVersion(), command.actionType(), command.allowedToolIds())
                : command.interventionHash();
        return runs.save(new InterventionRun(
                command.learnerId(), command.subjectId(), command.curriculumVersionId(),
                command.origin(), command.targetSpecificationPoints(), command.questionPartIds(),
                command.evidenceRefs(), command.diagnosisSnapshotRef(), command.learnerStateSnapshotRef(),
                command.diagnosisVersion(), command.actionType(), command.interventionVersion(),
                hash, command.allowedToolIds()));
    }

    @Transactional
    public InterventionRun activate(UUID runId) {
        InterventionRun run = get(runId);
        run.activate(Instant.now());
        return runs.save(run);
    }

    @Transactional
    public InterventionRun pause(UUID runId) {
        InterventionRun run = get(runId);
        run.pause();
        return runs.save(run);
    }

    @Transactional
    public InterventionRun resume(UUID runId, String interventionVersion, String interventionHash) {
        InterventionRun run = get(runId);
        if (!run.interventionVersion().equals(interventionVersion)
                || !run.interventionHash().equals(interventionHash)) {
            throw new InterventionVersionMismatchException(runId, run.interventionVersion(), run.interventionHash());
        }
        run.activate(Instant.now());
        return runs.save(run);
    }

    @Transactional
    public InterventionRunStep recordStep(UUID runId, StepCommand command) {
        InterventionRun run = get(runId);
        run.recordStep(command.sequenceNo());
        InterventionRunStep step = new InterventionRunStep(
                runId, command.sequenceNo(), command.status(), command.observationType(),
                command.inputEvidenceRef(), command.outputEvidenceRef(), command.blockedReason(),
                command.startedAt(), command.completedAt());
        runs.save(run);
        return steps.save(step);
    }

    @Transactional
    public InterventionRunEvidence attachEvidence(UUID runId, String evidenceRef, String role) {
        get(runId);
        return evidence.save(new InterventionRunEvidence(runId, evidenceRef, role, Instant.now()));
    }

    @Transactional
    public InterventionRun complete(UUID runId, String terminalOutcome) {
        InterventionRun run = get(runId);
        run.complete(terminalOutcome, Instant.now());
        return runs.save(run);
    }

    @Transactional
    public InterventionRun cancel(UUID runId) {
        InterventionRun run = get(runId);
        run.cancel(Instant.now());
        return runs.save(run);
    }

    @Transactional(readOnly = true)
    public InterventionRun get(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new IllegalArgumentException("Unknown intervention run: " + runId));
    }

    static String hashIntervention(String version, String actionType, String allowedToolIds) {
        String canonical = require(version) + "\n" + require(actionType) + "\n" + require(allowedToolIds);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required intervention identity is missing");
        return value;
    }

    public record CreateCommand(
            UUID learnerId,
            UUID subjectId,
            UUID curriculumVersionId,
            String origin,
            String targetSpecificationPoints,
            String questionPartIds,
            String evidenceRefs,
            String diagnosisSnapshotRef,
            String learnerStateSnapshotRef,
            String diagnosisVersion,
            String actionType,
            String interventionVersion,
            String interventionHash,
            String allowedToolIds) { }

    public record StepCommand(
            int sequenceNo,
            String status,
            String observationType,
            String inputEvidenceRef,
            String outputEvidenceRef,
            String blockedReason,
            Instant startedAt,
            Instant completedAt) { }
}

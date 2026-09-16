package com.syllabai.intervention;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.intervention.dto.InterventionRunViews.CompleteRequest;
import com.syllabai.intervention.dto.InterventionRunViews.EvidenceRequest;
import com.syllabai.intervention.dto.InterventionRunViews.ResumeRequest;
import com.syllabai.intervention.dto.InterventionRunViews.RunView;
import com.syllabai.intervention.dto.InterventionRunViews.StepRequest;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing InterventionRun surface (E2 prototype, issue #19 —
 * INTERVENTION_RUN_PROTOTYPE.md). Learner-scoped under /api/v1/learners/me;
 * every operation is ownership-checked (another learner's run is an
 * indistinguishable 404 — no existence oracle).
 *
 * <p>The run is a bounded, auditable execution record: it can REQUEST nothing
 * and MUTATE nothing. Recording steps, attaching evidence references and
 * completing a run write execution history only — learner-state mutation
 * remains exclusively in the governed learner-model path (contract §9). There
 * is deliberately no LLM, no tool execution and no workflow engine behind this
 * surface: the prototype's allowedToolIds describe the bounded tool set of the
 * intervention, they do not expose executable tools here.</p>
 *
 * <p>Fail-closed semantics: unknown/foreign run 404; state-machine violations
 * (terminal runs, completing a non-ACTIVE run, …) 409; resuming against a
 * materially different intervention definition 409
 * {@code intervention_version_mismatch} (contract §6); malformed requests 400.</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me/intervention-runs")
public class InterventionRunController {

    private final InterventionRunService runs;
    private final InterventionRunScenarioService scenario;

    public InterventionRunController(InterventionRunService runs,
                                     InterventionRunScenarioService scenario) {
        this.runs = runs;
        this.scenario = scenario;
    }

    /**
     * The E2 first scenario: create one practice run from the learner's own
     * deterministic next-best-action (measured weakness → NBA PRACTICE → run).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RunView createFromRecommendation(@CurrentUserId UUID learnerId,
                                            @RequestParam UUID rootId) {
        return inStateConflictTerms(() -> view(scenario.createFromRecommendation(learnerId, rootId),
                List.of(), List.of()));
    }

    /** Full reconstruction: run identity + ordered steps + evidence references. */
    @GetMapping("/{runId}")
    public RunView get(@CurrentUserId UUID learnerId, @PathVariable UUID runId) {
        InterventionRun run = ownedRun(learnerId, runId);
        return view(run, runs.stepsOf(runId), runs.evidenceOf(runId));
    }

    @PostMapping("/{runId}/activate")
    public RunView activate(@CurrentUserId UUID learnerId, @PathVariable UUID runId) {
        ownedRun(learnerId, runId);
        return inStateConflictTerms(() -> view(runs.activate(runId)));
    }

    @PostMapping("/{runId}/pause")
    public RunView pause(@CurrentUserId UUID learnerId, @PathVariable UUID runId) {
        ownedRun(learnerId, runId);
        return inStateConflictTerms(() -> view(runs.pause(runId)));
    }

    /** Resume requires the EXACT intervention identity the run was created with. */
    @PostMapping("/{runId}/resume")
    public RunView resume(@CurrentUserId UUID learnerId, @PathVariable UUID runId,
                          @RequestBody ResumeRequest request) {
        ownedRun(learnerId, runId);
        requireText(request.interventionVersion(), "interventionVersion");
        requireText(request.interventionHash(), "interventionHash");
        return inStateConflictTerms(() -> view(runs.resume(runId,
                request.interventionVersion(), request.interventionHash())));
    }

    /** One ordered step observation; the sequence number is server-assigned. */
    @PostMapping("/{runId}/steps")
    public RunView recordStep(@CurrentUserId UUID learnerId, @PathVariable UUID runId,
                              @RequestBody StepRequest request) {
        InterventionRun run = ownedRun(learnerId, runId);
        requireText(request.observationType(), "observationType");
        requireText(request.status() == null ? "DONE" : request.status(), "status");
        int next = run.currentStep() == null ? 0 : run.currentStep() + 1;
        return inStateConflictTerms(() -> {
            runs.recordStep(runId, new InterventionRunService.StepCommand(
                    next, request.status(), request.observationType(),
                    request.inputEvidenceRef(), request.outputEvidenceRef(),
                    request.blockedReason(), Instant.now(),
                    "DONE".equals(request.status()) ? Instant.now() : null));
            return view(runs.get(runId), runs.stepsOf(runId), runs.evidenceOf(runId));
        });
    }

    /** Attaches an evidence REFERENCE (the canonical record is never copied). */
    @PostMapping("/{runId}/evidence")
    public RunView attachEvidence(@CurrentUserId UUID learnerId, @PathVariable UUID runId,
                                  @RequestBody EvidenceRequest request) {
        ownedRun(learnerId, runId);
        requireText(request.evidenceRef(), "evidenceRef");
        requireText(request.role() == null ? "ATTEMPT_EVIDENCE" : request.role(), "role");
        return inStateConflictTerms(() -> {
            runs.attachEvidence(runId, request.evidenceRef(), request.role());
            return view(runs.get(runId), runs.stepsOf(runId), runs.evidenceOf(runId));
        });
    }

    @PostMapping("/{runId}/complete")
    public RunView complete(@CurrentUserId UUID learnerId, @PathVariable UUID runId,
                            @RequestBody CompleteRequest request) {
        ownedRun(learnerId, runId);
        requireText(request.terminalOutcome(), "terminalOutcome");
        return inStateConflictTerms(() -> view(runs.complete(runId, request.terminalOutcome())));
    }

    @PostMapping("/{runId}/cancel")
    public RunView cancel(@CurrentUserId UUID learnerId, @PathVariable UUID runId) {
        ownedRun(learnerId, runId);
        return inStateConflictTerms(() -> view(runs.cancel(runId)));
    }

    // ------------------------------------------------------------------

    private InterventionRun ownedRun(UUID learnerId, UUID runId) {
        InterventionRun run = runs.get(runId);
        if (!run.learnerId().equals(learnerId)) {
            // no existence oracle across learners
            throw new NotFoundException("intervention run", runId);
        }
        return run;
    }

    /**
     * The E2 status machine (terminal runs, transitions from the wrong state)
     * is a CLIENT conflict (409), not a server error — the aggregate signals it
     * with IllegalStateException; this boundary translates it honestly. The
     * version-mismatch subclass passes through UNCHANGED so its NAMED 409 body
     * (intervention_version_mismatch — the client must start a new run, not
     * retry) reaches the client (GlobalExceptionHandler).
     */
    private RunView inStateConflictTerms(RunCall call) {
        try {
            return call.run();
        } catch (InterventionVersionMismatchException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
        }
    }

    @FunctionalInterface
    private interface RunCall {
        RunView run();
    }

    private RunView view(InterventionRun run) {
        return view(run, List.of(), List.of());
    }

    private RunView view(InterventionRun run,
                         List<InterventionRunStep> steps,
                         List<InterventionRunEvidence> evidence) {
        return new RunView(
                run.runId(), run.learnerId(), run.subjectId(), run.curriculumVersionId(),
                run.status().name(), run.origin(),
                jsonArrayList(run.targetSpecificationPoints()),
                jsonArrayList(run.questionPartIds()),
                jsonArrayList(run.evidenceRefs()),
                run.diagnosisSnapshotRef(), run.learnerStateSnapshotRef(),
                run.diagnosisVersion(), run.actionType(),
                run.interventionVersion(), run.interventionHash(),
                jsonArrayList(run.allowedToolIds()),
                run.terminalOutcome(), run.currentStep(),
                run.createdAt(), run.startedAt(), run.completedAt(), run.cancelledAt(),
                steps.stream().map(s -> new RunView.StepView(s.stepId(), s.sequenceNo(),
                        s.status(), s.observationType(), s.inputEvidenceRef(),
                        s.outputEvidenceRef(), s.blockedReason(),
                        s.startedAt(), s.completedAt())).toList(),
                evidence.stream().map(e -> new RunView.EvidenceView(e.id(),
                        e.evidenceRef(), e.role(), e.capturedAt())).toList());
    }

    /** The run's JSON-array columns are controlled shapes written by this app. */
    private static List<String> jsonArrayList(String json) {
        if (json == null) {
            return List.of();
        }
        String body = json.strip();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : body.split(",")) {
            String value = part.strip();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " is required");
        }
    }
}

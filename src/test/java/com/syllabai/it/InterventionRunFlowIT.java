package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.dto.PartAnswerRequest;
import com.syllabai.assessment.dto.StructuredSubmitRequest;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.intervention.InterventionRun;
import com.syllabai.intervention.InterventionRunController;
import com.syllabai.intervention.InterventionRunScenarioService;
import com.syllabai.intervention.InterventionVersionMismatchException;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.recommendation.NextBestActionService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import com.syllabai.recommendation.dto.NextBestActionsView.ActionType;
import com.syllabai.recommendation.dto.NextBestActionsView.ReasonCode;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ContentReviewService;
import com.syllabai.teacher.TeacherMarkingService;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: the E2 first scenario (INTERVENTION_RUN_PROTOTYPE.md §11,
 * issue #19) over a real Postgres — measured weak SpecificationPoint →
 * deterministic NBA PRACTICE action → InterventionRun created FROM that
 * recommendation snapshot → ordered step observations → existing attempt
 * evidence attached BY REFERENCE → terminal completion → reconstruction.
 *
 * <p>Boundaries proven at the DB level: completing a run (and every other run
 * operation) never mutates learner state — the governed learner-model path is
 * the only mastery authority; the run stores execution identity only. Resume
 * against a materially different intervention definition fails closed;
 * terminal history is append-only at the domain level. Runs in CI where
 * Docker exists; skipped locally otherwise.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class InterventionRunFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired
    private PastPaperIngestionService ingestion;
    @Autowired
    private ContentReviewService review;
    @Autowired
    private AssessmentService assessment;
    @Autowired
    private TeacherMarkingService teacherMarkingService;
    @Autowired
    private AuthService authService;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private QuestionVersionRepository questionVersions;
    @Autowired
    private MarkSchemeRepository markSchemes;
    @Autowired
    private AnswerRepository answers;
    @Autowired
    private NextBestActionService nextBestActions;
    @Autowired
    private SubjectRepository subjects;
    @Autowired
    private SkillStateRepository skillStates;
    @Autowired
    private InterventionRunScenarioService scenario;
    @Autowired
    private InterventionRunController controller;

    private static PastPaperDraftDto draft(String paperCode) {
        return new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry",
                        "Paper 2C", "June 2013-" + UUID.randomUUID().toString().substring(0, 6),
                        paperCode, "it-qp-doc", "it-ms-doc"),
                List.of(new PastPaperDraftDto.QuestionDraft("q1", "1", "Question 1 stem",
                        "Explain", 2, "STRUCTURED", 1, 0.6,
                        List.of(new PastPaperDraftDto.PartDraft("a", "Part a prompt",
                                "State", 2, 0.6)))),
                new PastPaperDraftDto.MarkSchemeDraft("1", "it-ms-doc", List.of(
                        new PastPaperDraftDto.MarkPointDraft("1-a", 1, "the correct content", 2,
                                List.of(), 0.6))),
                "it-test-method",
                true);
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-run-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    private void validateCurrentVersion(Question question) {
        var version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        review.validateQuestionVersion(version.id());
        var scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
        review.validateMarkScheme(scheme.id(), List.of(new ContentReviewService.PointCriteria(
                scheme.points().get(0).id(), List.of("the correct content"))));
    }

    /** one wrong, human-marked-zero attempt on the question's first part */
    private UUID wrongAttempt(UUID learner, Question question) {
        var version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        UUID partId = version.parts().get(0).id();
        var result = assessment.submitStructured(learner,
                new StructuredSubmitRequest(question.id(),
                        List.of(new PartAnswerRequest(partId, "a wrong answer")),
                        30000L, 4, false, true));
        UUID answerId = answers.findByAttemptIdOrderByQuestionPartId(
                result.attemptId()).get(0).id();
        var scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
        Map<String, Integer> zero = new LinkedHashMap<>();
        zero.put(scheme.points().get(0).id().toString(), 0);
        teacherMarkingService.recordHumanMark(answerId, UUID.randomUUID(), 0, zero, "no marks");
        return result.attemptId();
    }

    @Test
    @DisplayName("E2 §11 scenario: NBA practice run → steps → evidence by reference → terminal → reconstruct; run ops never mutate learner state")
    void recommendationToRunToTerminalReconstruction() {
        // 1. real validated content + measured weakness (two zero-mark attempts)
        PastPaperIngestionService.IngestionSummary summary =
                ingestion.ingest(draft("RUNA"), UUID.randomUUID());
        Question question = questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> summary.paperId().equals(q.examPaperId()))
                .findFirst().orElseThrow();
        validateCurrentVersion(question);
        UUID learner = newLearner();
        UUID rootId = question.primaryTopicNodeId();
        UUID attempt1 = wrongAttempt(learner, question);
        wrongAttempt(learner, question);

        // the deterministic NBA PRACTICE action exists (measured weakness)
        NextBestActionsView nba = nextBestActions.actionsFor(learner, rootId);
        assertThat(nba.actions())
                .anySatisfy(a -> {
                    assertThat(a.actionType()).isEqualTo(ActionType.PRACTISE_QUESTIONS);
                    assertThat(a.reasonCode()).isEqualTo(ReasonCode.LOW_MASTERY);
                    assertThat(a.targetNodeId()).isEqualTo(rootId);
                });

        // 2. create the run FROM the recommendation snapshot
        InterventionRun run = scenario.createFromRecommendation(learner, rootId);
        assertThat(run.status().name()).isEqualTo("CREATED");
        assertThat(run.origin()).isEqualTo("NBA");
        assertThat(run.learnerId()).isEqualTo(learner);
        assertThat(run.subjectId())
                .isEqualTo(subjects.findByKnowledgeNodeId(rootId).orElseThrow().id());
        assertThat(run.targetSpecificationPoints()).contains(rootId.toString());
        assertThat(run.diagnosisVersion()).isEqualTo(nba.policy());
        assertThat(run.diagnosisSnapshotRef()).startsWith("nba:" + nba.policy() + ":");
        assertThat(run.learnerStateSnapshotRef()).startsWith("skill-state:" + rootId + ":a");
        assertThat(run.actionType()).isEqualTo("PRACTISE_QUESTIONS");
        assertThat(run.interventionVersion())
                .isEqualTo(InterventionRunScenarioService.INTERVENTION_VERSION);
        assertThat(run.allowedToolIds())
                .isEqualTo(InterventionRunScenarioService.ALLOWED_TOOLS_JSON);
        assertThat(run.interventionHash()).hasSize(64);

        // 3. learner-state snapshot before any run operation
        SkillState before = skillStates
                .findByLearnerIdAndNodeId(learner, rootId).orElseThrow();
        long versionBefore = before.updatedAt().toEpochMilli();
        int attemptsBefore = before.attempts();

        // 4. ordered step observations + existing attempt evidence BY REFERENCE
        controller.activate(learner, run.runId());
        controller.recordStep(learner, run.runId(),
                new com.syllabai.intervention.dto.InterventionRunViews.StepRequest(
                        "DONE", "PRACTICE_SUBMITTED", null,
                        "attempt:" + attempt1, null));
        controller.attachEvidence(learner, run.runId(),
                new com.syllabai.intervention.dto.InterventionRunViews.EvidenceRequest(
                        "attempt:" + attempt1, "ATTEMPT_EVIDENCE"));
        controller.complete(learner, run.runId(),
                new com.syllabai.intervention.dto.InterventionRunViews.CompleteRequest(
                        "EVIDENCE_COLLECTED"));

        // 5. reconstruction: identity + ordered steps + evidence references
        var view = controller.get(learner, run.runId());
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.terminalOutcome()).isEqualTo("EVIDENCE_COLLECTED");
        assertThat(view.completedAt()).isNotNull();
        assertThat(view.steps()).hasSize(1);
        assertThat(view.steps().get(0).sequenceNo()).isZero();
        assertThat(view.steps().get(0).outputEvidenceRef()).isEqualTo("attempt:" + attempt1);
        assertThat(view.evidence()).hasSize(1);
        assertThat(view.evidence().get(0).evidenceRef()).isEqualTo("attempt:" + attempt1);
        assertThat(view.evidence().get(0).role()).isEqualTo("ATTEMPT_EVIDENCE");
        assertThat(view.targetSpecificationPoints()).containsExactly(rootId.toString());
        assertThat(view.allowedToolIds()).containsExactly(
                "get_specification_context", "get_learner_state", "start_practice");

        // 6. MUTATION BOUNDARY (E2 criteria 6+7): the run operations themselves
        //    changed NO learner state — the attempts' governed evidence path
        //    did its work BEFORE the run existed; run completion moved nothing.
        SkillState after = skillStates
                .findByLearnerIdAndNodeId(learner, rootId).orElseThrow();
        assertThat(after.updatedAt().toEpochMilli()).isEqualTo(versionBefore);
        assertThat(after.attempts()).isEqualTo(attemptsBefore);
        assertThat(after.mastery()).isEqualTo(before.mastery());

        // 7. append-only terminal history: a second completion and a late
        //    evidence attachment both fail closed (409 at the boundary)
        assertThatThrownBy(() -> controller.complete(learner, run.runId(),
                new com.syllabai.intervention.dto.InterventionRunViews.CompleteRequest(
                        "EVIDENCE_COLLECTED")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> controller.attachEvidence(learner, run.runId(),
                new com.syllabai.intervention.dto.InterventionRunViews.EvidenceRequest(
                        "attempt:x", "ATTEMPT_EVIDENCE")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("resume fails closed on intervention identity mismatch; ownership is fail-closed 404; cold-start UNCOVERED_TOPIC practice run")
    void resumeVersionGateOwnershipAndColdStart() {
        // cold start: validated content exists, learner has no evidence
        PastPaperIngestionService.IngestionSummary summary =
                ingestion.ingest(draft("RUNB"), UUID.randomUUID());
        Question question = questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> summary.paperId().equals(q.examPaperId()))
                .findFirst().orElseThrow();
        validateCurrentVersion(question);
        UUID learner = newLearner();
        UUID rootId = question.primaryTopicNodeId();

        NextBestActionsView cold = nextBestActions.actionsFor(learner, rootId);
        assertThat(cold.actions().get(0).reasonCode()).isEqualTo(ReasonCode.UNCOVERED_TOPIC);
        assertThat(cold.actions().get(0).actionType())
                .isEqualTo(ActionType.PRACTISE_QUESTIONS);

        InterventionRun run = scenario.createFromRecommendation(learner, rootId);
        assertThat(run.learnerStateSnapshotRef())
                .isEqualTo("skill-state:" + rootId + ":unmeasured");
        assertThat(skillStates.countByLearnerId(learner)).isZero();

        // resume gate: wrong identity fails closed, right identity resumes
        controller.activate(learner, run.runId());
        controller.pause(learner, run.runId());
        String version = run.interventionVersion();
        String wrongHash = "0".repeat(64);
        assertThatThrownBy(() -> controller.resume(learner, run.runId(),
                new com.syllabai.intervention.dto.InterventionRunViews.ResumeRequest(
                        version, wrongHash)))
                .isInstanceOf(InterventionVersionMismatchException.class);
        controller.resume(learner, run.runId(),
                new com.syllabai.intervention.dto.InterventionRunViews.ResumeRequest(
                        version, run.interventionHash()));
        assertThat(controller.get(learner, run.runId()).status()).isEqualTo("ACTIVE");

        // ownership: another learner's run is an indistinguishable 404
        UUID stranger = newLearner();
        assertThatThrownBy(() -> controller.get(stranger, run.runId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.complete(stranger, run.runId(),
                new com.syllabai.intervention.dto.InterventionRunViews.CompleteRequest("X")))
                .isInstanceOf(NotFoundException.class);

        // the stranger's run operations mutated nothing on the owner's run
        assertThat(controller.get(learner, run.runId()).status()).isEqualTo("ACTIVE");

        // completing this run also leaves the unmeasured learner unmutated
        controller.complete(learner, run.runId(),
                new com.syllabai.intervention.dto.InterventionRunViews.CompleteRequest(
                        "EVIDENCE_COLLECTED"));
        assertThat(skillStates.countByLearnerId(learner)).isZero();
    }
}

package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.dto.PartAnswerRequest;
import com.syllabai.assessment.dto.StructuredSubmitRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.recommendation.LearnerRecommendationController;
import com.syllabai.recommendation.NextBestActionService;
import com.syllabai.recommendation.dto.NextBestActionsView;
import com.syllabai.recommendation.dto.NextBestActionsView.ActionType;
import com.syllabai.recommendation.dto.NextBestActionsView.ReasonCode;
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
 * Integration test: the T-033 next-best-action loop against a real Postgres —
 * evidence in, ranked evidence-backed actions out (ADR-017 nba-rules/v1.1
 * baseline). Runs in CI where Docker exists; skipped locally otherwise.
 *
 * <p>Covers: low-mark human-marked evidence → RETRY_PROBLEM_QUESTION with the
 * measured marks in the reason; the learner-serving boundary (an UNVALIDATED
 * question is not even attemptable — fe98ea9 write-path fail-closed 404 — and
 * never surfaces as a recommendation); subject-subtree
 * scoping (evidence under another anchor is invisible); determinism (two calls
 * produce identical ranked actions); honest cold start (a new learner gets an
 * UNCOVERED_TOPIC action when validated questions exist, never a crash); and
 * the controller wiring for GET /api/v1/learners/me/recommendations.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class NextBestActionFlowIT {

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
    private LearnerRecommendationController recommendationController;

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
                "it-nba-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    private Question questionOf(UUID paperId) {
        return questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> paperId.equals(q.examPaperId()))
                .findFirst().orElseThrow();
    }

    private void validateCurrentVersion(Question question) {
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        review.validateQuestionVersion(version.id());
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
        review.validateMarkScheme(scheme.id(), List.of(new ContentReviewService.PointCriteria(
                scheme.points().get(0).id(), List.of("the correct content"))));
    }

    /** one wrong, human-marked-zero attempt on the question's first part */
    private UUID wrongAttempt(UUID learner, Question question) {
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        UUID partId = version.parts().get(0).id();
        var result = assessment.submitStructured(learner,
                new StructuredSubmitRequest(question.id(),
                        List.of(new PartAnswerRequest(partId, "a wrong answer")),
                        30000L, 4, false, true));
        UUID answerId = answers.findByAttemptIdOrderByQuestionPartId(
                result.attemptId()).get(0).id();
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
        Map<String, Integer> zero = new LinkedHashMap<>();
        zero.put(scheme.points().get(0).id().toString(), 0);
        teacherMarkingService.recordHumanMark(answerId, UUID.randomUUID(), 0, zero, "no marks");
        return answerId;
    }

    @Test
    @DisplayName("low-mark evidence → ranked retry action; unvalidated questions never recommended; deterministic; honest cold start")
    void evidenceToRankedActions() {
        UUID learner = newLearner();

        // 1. two drafts: A validated (servable), B left SUGGESTED (boundary probe)
        PastPaperIngestionService.IngestionSummary summaryA =
                ingestion.ingest(draft("NBAA"), UUID.randomUUID());
        PastPaperIngestionService.IngestionSummary summaryB =
                ingestion.ingest(draft("NBAB"), UUID.randomUUID());
        Question questionA = questionOf(summaryA.paperId());
        Question questionB = questionOf(summaryB.paperId());
        validateCurrentVersion(questionA);

        // 2. wrong zero-mark attempt on the validated question (the anchor TOPIC
        //    under each paper is the practice scope; distinct paper codes keep
        //    anchors distinct)
        UUID answerA = wrongAttempt(learner, questionA);
        assertThat(answers.findWithPartAndAttempt(answerA).orElseThrow().markingState())
                .isEqualTo(Answer.MarkingState.HUMAN_MARKED);

        // 2b. the write-path serving boundary (fe98ea9): the UNVALIDATED question
        //     is not even attemptable — fail-closed NotFound, no part labels, no
        //     marks, no state echo. The old contract (attemptable but never
        //     recommended) is superseded; the read-path boundary stays asserted
        //     by the noneMatch checks below.
        QuestionVersion versionB = questionVersions
                .findByQuestionIdOrderByVersionDesc(questionB.id()).get(0);
        UUID partB = versionB.parts().get(0).id();
        assertThatThrownBy(() -> assessment.submitStructured(learner,
                new StructuredSubmitRequest(questionB.id(),
                        List.of(new PartAnswerRequest(partB, "a wrong answer")),
                        30000L, 4, false, true)))
                .isInstanceOf(NotFoundException.class);

        // 3. scoped to question A's anchor: the validated question becomes a retry
        UUID rootA = questionA.primaryTopicNodeId();
        NextBestActionsView view = nextBestActions.actionsFor(learner, rootA);
        assertThat(view.policy()).isEqualTo("nba-rules/v1.2");
        assertThat(view.actions()).isNotEmpty();
        assertThat(view.actions().stream()
                .filter(a -> a.actionType() == ActionType.RETRY_PROBLEM_QUESTION))
                .singleElement()
                .satisfies(retry -> {
                    assertThat(retry.questionId()).isEqualTo(questionA.id());
                    assertThat(retry.reasonCode()).isEqualTo(ReasonCode.PROBLEM_QUESTION);
                    assertThat(retry.reasonDetail()).contains("0/2");
                });
        // one evidence item is below the LOW_MASTERY floor (min 2 attempts) — no
        // LOW_MASTERY action may appear from a single attempt
        assertThat(view.actions()).noneMatch(a -> a.reasonCode() == ReasonCode.LOW_MASTERY);

        // 4. the boundary proof on the read path: scoped to question B's anchor,
        //    the UNVALIDATED question must never be recommended (its evidence
        //    cannot even exist — see 2b)
        UUID rootB = questionB.primaryTopicNodeId();
        NextBestActionsView viewB = nextBestActions.actionsFor(learner, rootB);
        assertThat(viewB.actions()).noneMatch(a -> questionB.id().equals(a.questionId()));

        // 5. subject scoping: A's evidence is invisible under B's anchor and vice versa
        assertThat(viewB.actions()).noneMatch(a -> questionA.id().equals(a.questionId()));
        assertThat(view.actions()).noneMatch(a -> questionB.id().equals(a.questionId()));

        // 6. deterministic: same learner state ⇒ identical ranked actions
        assertThat(nextBestActions.actionsFor(learner, rootA).actions())
                .containsExactlyElementsOf(view.actions());

        // 7. the controller wiring (GET /api/v1/learners/me/recommendations)
        assertThat(recommendationController.recommendations(learner, rootA).actions())
                .containsExactlyElementsOf(view.actions());

        // 8. honest cold start: a brand-new learner under root A gets uncovered-topic
        //    practice (validated questions exist), never a fabricated weakness
        UUID newcomer = newLearner();
        NextBestActionsView cold = nextBestActions.actionsFor(newcomer, rootA);
        assertThat(cold.actions()).isNotEmpty();
        assertThat(cold.actions().get(0).reasonCode()).isEqualTo(ReasonCode.UNCOVERED_TOPIC);
        assertThat(cold.actions().get(0).servableQuestionCount()).isEqualTo(1);
        assertThat(cold.actions()).noneMatch(a -> a.reasonCode() == ReasonCode.LOW_MASTERY);
    }
}

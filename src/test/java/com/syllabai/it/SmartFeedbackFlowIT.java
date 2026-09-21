package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.dto.PartAnswerRequest;
import com.syllabai.assessment.dto.StructuredAttemptResultView;
import com.syllabai.assessment.dto.StructuredSubmitRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.infrastructure.llm.LlmProvider;
import com.syllabai.infrastructure.llm.LlmResponse;
import com.syllabai.smartmark.SmartMarkResult;
import com.syllabai.smartmark.SmartMarkService;
import com.syllabai.smartmark.StudentSmartMarkService;
import com.syllabai.teacher.ContentReviewService;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test (session-113): the learner feedback actions
 * ("Explain my feedback" / "Improve my answer") against real Postgres with
 * OSIV off — the exact production failure surface.
 *
 * <p>Production 500s were first exercised by the s113 E2E probe: both actions
 * read the scheme's LAZY points collection and the part's prompt through the
 * DETACHED loadFeedbackSource graph with no transaction on the service method,
 * dying on LazyInitializationException behind the generic 500 handler. The
 * fix is @Transactional on both methods; this IT runs them through the real
 * JPA stack so the detached-load failure mode can never return silently
 * (unit tests mock the repositories and cannot see it).</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class SmartFeedbackFlowIT {

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
    private SmartMarkService smartMarkService;
    @Autowired
    private StudentSmartMarkService studentSmartMarkService;
    @Autowired
    private AuthService authService;
    @Autowired
    private ExamPaperRepository examPapers;
    @Autowired
    private QuestionRepository questions;
    @Autowired
    private QuestionVersionRepository questionVersions;
    @Autowired
    private MarkSchemeRepository markSchemes;
    @Autowired
    private AnswerRepository answers;

    /** the feedback generations run against a stubbed chain — the marking itself needs no LLM */
    @MockitoBean
    private LlmProvider llm;

    private static PastPaperDraftDto draft() {
        return new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry",
                        "Paper 2C", "June 2014-" + UUID.randomUUID().toString().substring(0, 6),
                        "4SF" + UUID.randomUUID().toString().substring(0, 4),
                        "it-qp-doc", "it-ms-doc"),
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
                "it-fb-learner-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    @Test
    @DisplayName("feedback actions survive the detached load — no LazyInitializationException (production 500)")
    void feedbackActionsRunThroughRealJpaWithoutLazyFailure() {
        when(llm.available()).thenReturn(true);
        when(llm.generate(any())).thenReturn(new LlmResponse(
                "You earned 0 of 2 marks: the required content was missing.",
                "stub", "stub-model", 10L, 10, 10));

        UUID learner = newLearner();

        // 1. ingest + validate (MultipartMarkingFlowIT pattern)
        PastPaperIngestionService.IngestionSummary summary =
                ingestion.ingest(draft(), UUID.randomUUID());
        Question question = questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> summary.paperId().equals(q.examPaperId()))
                .findFirst().orElseThrow();
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        review.validateQuestionVersion(version.id());
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
        review.validateMarkScheme(scheme.id(), List.of(new ContentReviewService.PointCriteria(
                scheme.points().get(0).id(), List.of("the correct content"))));
        assertThat(markSchemes.findWithPoints(scheme.id()).orElseThrow().validationState())
                .isEqualTo(MarkScheme.ValidationState.VALIDATED);

        // 2. blank structured submission → deterministic accepted smart mark
        //    (short-circuit: no LLM call, zero marks, per-point breakdown rows)
        UUID partId = version.parts().get(0).id();
        StructuredAttemptResultView submitted = assessment.submitStructured(learner,
                new StructuredSubmitRequest(question.id(),
                        List.of(new PartAnswerRequest(partId, "   ")),
                        30000L, 3, false, false));
        UUID answerId = answers.findByAttemptIdOrderByQuestionPartId(
                submitted.attemptId()).get(0).id();
        SmartMarkResult run = smartMarkService.markAnswer(answerId);
        assertThat(run.validationPassed()).isTrue();
        assertThat(run.marksAwarded()).isZero();

        // 3. THE REGRESSION: both actions load the scheme's LAZY points through
        //    loadFeedbackSource — before 931eb73 they ran outside any session and
        //    threw LazyInitializationException behind the generic 500
        var explanation = studentSmartMarkService.explainFeedback(
                learner, submitted.attemptId(), partId);
        assertThat(explanation.explanation()).contains("0 of 2");
        assertThat(explanation.partId()).isEqualTo(partId);

        var plan = studentSmartMarkService.improvementPlan(
                learner, submitted.attemptId(), partId);
        assertThat(plan.plan()).contains("0 of 2");
        assertThat(plan.partId()).isEqualTo(partId);
    }
}

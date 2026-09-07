package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.AttemptHistoryController;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.dto.AttemptHistoryView;
import com.syllabai.assessment.dto.PartAnswerRequest;
import com.syllabai.assessment.dto.StructuredSubmitRequest;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
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
 * Integration test: the attempt-history read model (Review Hub minimal slice,
 * charter §14) against a real Postgres. History is strictly a view over the
 * attempts/answers evidence rows — this IT pins that contract end to end.
 *
 * <p>Covers: a seeded-MCQ attempt resolves outcome, chosen + correct option
 * labels, the distractor's misconception, AUTO_GRADED state and evidence
 * emission; a structured attempt is PENDING with null marks and labeled
 * parts until a human mark lands, then the history reflects the summed
 * authoritative total; per-learner isolation (another learner's history
 * stays empty); the cap/limit contract; and the controller wiring for
 * GET /api/v1/learners/me/attempts.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class AttemptHistoryFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    /** V7 seed: SEED-WCH11-001 (mass of 0.25 mol CaCO3) — deterministic fixture. */
    private static final UUID SEED_MCQ = UUID.fromString("40000000-0000-0000-0000-000000000001");
    /** V7 seed: option A "0.25 g" — wrong and tagged with MIS-T1.1-01. */
    private static final UUID SEED_MCQ_WRONG_OPTION =
            UUID.fromString("41000000-0000-0000-0000-000000000001");

    @Autowired
    private PastPaperIngestionService ingestion;
    @Autowired
    private AssessmentService assessment;
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
    private TeacherMarkingService teacherMarkingService;
    @Autowired
    private ContentReviewService review;
    @Autowired
    private AttemptHistoryController historyController;

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-hist-" + UUID.randomUUID().toString().substring(0, 8) + "@syllabai.test",
                "ItLearner123!", "It Learner")).user().id();
    }

    @Test
    @DisplayName("MCQ attempt appears with outcome, option labels and misconception; history is learner-scoped; limit caps rows")
    void mcqAttemptHistory() {
        UUID learner = newLearner();
        UUID other = newLearner();

        assessment.submit(learner, new SubmitAnswerRequest(
                SEED_MCQ, SEED_MCQ_WRONG_OPTION, 25_000L, 4, false, false));

        AttemptHistoryView view = historyController.attempts(learner, null);

        assertThat(view.learnerId()).isEqualTo(learner);
        assertThat(view.total()).isEqualTo(1);
        assertThat(view.attempts()).singleElement().satisfies(item -> {
            assertThat(item.questionId()).isEqualTo(SEED_MCQ);
            assertThat(item.correct()).isFalse();
            assertThat(item.marksAwarded()).isZero();
            assertThat(item.marksTotal()).isEqualTo(1);
            assertThat(item.markingState()).isEqualTo("AUTO_GRADED");
            assertThat(item.evidenceEmitted()).isTrue();
            assertThat(item.chosenOptionLabel()).isEqualTo("A");
            assertThat(item.correctOptionLabel()).isEqualTo("C");
            assertThat(item.implicatedMisconceptionIds())
                    .containsExactly(UUID.fromString("30000000-0000-0000-0000-000000000001"));
            assertThat(item.topicNodeId()).isNotNull();
            assertThat(item.topicCode()).isNotBlank();
            assertThat(item.topicTitle()).isNotBlank();
            assertThat(item.parts()).isEmpty();
            assertThat(item.responseTimeMs()).isEqualTo(25_000L);
        });

        // learner isolation — another learner's history stays empty (honest cold start)
        assertThat(historyController.attempts(other, null).total()).isZero();
        assertThat(historyController.attempts(other, null).attempts()).isEmpty();
    }

    @Test
    @DisplayName("structured attempt: pending history with null marks, then the summed authoritative total after the human mark")
    void structuredAttemptPendingThenMarked() {
        UUID learner = newLearner();

        // one ingested, validated structured question (unique paper code per test run)
        PastPaperIngestionService.IngestionSummary summary =
                ingestion.ingest(draft("HIST" + UUID.randomUUID().toString().substring(0, 4)),
                        UUID.randomUUID());
        UUID questionId = questionOf(summary.paperId());
        UUID partId = partIdOf(questionId);
        UUID markPointId = validateCurrentVersion(questionId);

        var result = assessment.submitStructured(learner, new StructuredSubmitRequest(
                questionId, List.of(new PartAnswerRequest(partId, "a wrong answer")),
                40_000L, 3, true, true));

        // pending: no honest attempt-level marks exist yet
        AttemptHistoryView pending = historyController.attempts(learner, null);
        assertThat(pending.attempts()).singleElement().satisfies(item -> {
            assertThat(item.correct()).isNull();
            assertThat(item.marksAwarded()).isNull();
            assertThat(item.markingState()).isEqualTo("PENDING");
            assertThat(item.evidenceEmitted()).isFalse();
            assertThat(item.chosenOptionLabel()).isNull();
            assertThat(item.parts()).hasSize(1);
            assertThat(item.parts().get(0).label()).isEqualTo("a");
            assertThat(item.parts().get(0).marksPossible()).isEqualTo(2);
            assertThat(item.parts().get(0).marksAwarded()).isNull();
            assertThat(item.parts().get(0).markingState()).isEqualTo("PENDING");
        });

        // the teacher marks it: full marks → history reflects the authoritative total
        UUID answerId = answers.findByAttemptIdOrderByQuestionPartId(result.attemptId())
                .get(0).id();
        Map<String, Integer> full = new LinkedHashMap<>();
        full.put(markPointId.toString(), 2);
        teacherMarkingService.recordHumanMark(answerId, UUID.randomUUID(), 2, full, "both marks");

        AttemptHistoryView marked = historyController.attempts(learner, null);
        assertThat(marked.attempts()).singleElement().satisfies(item -> {
            assertThat(item.marksAwarded()).isEqualTo(2);
            assertThat(item.markingState()).isEqualTo("HUMAN_MARKED");
            assertThat(item.evidenceEmitted()).isTrue();
            assertThat(item.parts().get(0).marksAwarded()).isEqualTo(2);
            assertThat(item.parts().get(0).markingState()).isEqualTo("HUMAN_MARKED");
        });

        // limit contract: the advisory cap bounds rows but keeps the true total
        assessment.submit(learner, new SubmitAnswerRequest(
                SEED_MCQ, SEED_MCQ_WRONG_OPTION, 10_000L, 4, false, false));
        AttemptHistoryView capped = historyController.attempts(learner, 1);
        assertThat(capped.total()).isEqualTo(2);
        assertThat(capped.returned()).isEqualTo(1);
        assertThat(capped.attempts()).hasSize(1);
    }

    // ── shared ingestion fixtures (same shape as NextBestActionFlowIT) ──

    private UUID questionOf(UUID paperId) {
        return questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> paperId.equals(q.examPaperId()))
                .findFirst().orElseThrow().id();
    }

    private UUID partIdOf(UUID questionId) {
        return questionVersions.findByQuestionIdOrderByVersionDesc(questionId)
                .get(0).parts().get(0).id();
    }

    /** validates the current version + mark scheme; returns the first mark point id */
    private UUID validateCurrentVersion(UUID questionId) {
        var version = questionVersions.findByQuestionIdOrderByVersionDesc(questionId).get(0);
        review.validateQuestionVersion(version.id());
        var scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()).orElseThrow();
        review.validateMarkScheme(scheme.id(), List.of(new ContentReviewService.PointCriteria(
                scheme.points().get(0).id(), List.of("the correct content"))));
        return scheme.points().get(0).id();
    }

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
}

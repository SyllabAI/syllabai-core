package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.dto.PartAnswerRequest;
import com.syllabai.assessment.dto.StructuredAttemptResultView;
import com.syllabai.assessment.dto.StructuredSubmitRequest;
import com.syllabai.identity.AuthService;
import com.syllabai.identity.dto.RegisterRequest;
import com.syllabai.learner.SkillState;
import com.syllabai.learner.SkillStateRepository;
import com.syllabai.teacher.ContentReviewService;
import com.syllabai.teacher.TeacherMarkingService;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
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
 * Database-backed concurrency acceptance tests for the structured evidence contract.
 *
 * <p>These tests deliberately exercise separate Spring transactions against real
 * Postgres. The invariant is stronger than publisher-level idempotence: one settled
 * attempt must produce one evidence transition and one learner-model observation,
 * regardless of concurrent authoritative marking order.</p>
 *
 * <p>Runs in CI where Docker exists; skipped locally otherwise.</p>
 */
@SpringBootTest
@ActiveProfiles("it")
@Testcontainers(disabledWithoutDocker = true)
class EvidenceStateConcurrencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("syllabai")
                    .withUsername("syllabai")
                    .withPassword("syllabai");

    @Autowired private PastPaperIngestionService ingestion;
    @Autowired private ContentReviewService review;
    @Autowired private AssessmentService assessment;
    @Autowired private TeacherMarkingService teacherMarkingService;
    @Autowired private AuthService authService;
    @Autowired private AnswerRepository answers;
    @Autowired private AttemptRepository attempts;
    @Autowired private QuestionRepository questions;
    @Autowired private QuestionVersionRepository questionVersions;
    @Autowired private MarkSchemeRepository markSchemes;
    @Autowired private SkillStateRepository skillStates;

    private ExecutorService executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("same-answer concurrent authoritative marks emit evidence exactly once")
    void sameAnswerConcurrentMarksEmitEvidenceExactlyOnce() throws Exception {
        UUID learner = newLearner();
        Question question = validatedQuestion("4CH0/CONC-SAME");
        StructuredAttemptResultView submitted = submit(learner, question, List.of("answer"));
        UUID answerId = answerIds(submitted).get(0);

        ConcurrentResults results = runConcurrently(
                () -> teacherMarkingService.recordHumanMark(
                        answerId, UUID.randomUUID(), 1, Map.of(), "concurrent-a"),
                () -> teacherMarkingService.recordHumanMark(
                        answerId, UUID.randomUUID(), 1, Map.of(), "concurrent-b"));

        assertThat(results.failures()).isEmpty();
        SkillState state = skillStates
                .findByLearnerIdAndNodeId(learner, question.primaryTopicNodeId())
                .orElseThrow();
        assertThat(state.attempts()).as("one learner-model observation per attempt")
                .isEqualTo(1);
        assertThat(state.correctCount()).isEqualTo(1);
        assertThat(attempts.findById(submitted.attemptId()).orElseThrow().evidenceEmitted())
                .isTrue();

        // A retry/override after both concurrent transactions must remain evidence-idempotent.
        teacherMarkingService.recordHumanMark(
                answerId, UUID.randomUUID(), 1, Map.of(), "post-conflict-retry");
        SkillState afterRetry = skillStates
                .findByLearnerIdAndNodeId(learner, question.primaryTopicNodeId())
                .orElseThrow();
        assertThat(afterRetry.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("different-part concurrent marks settle the attempt and emit one final aggregate")
    void differentPartConcurrentMarksEmitOneFinalAggregate() throws Exception {
        UUID learner = newLearner();
        Question question = validatedTwoPartQuestion("4CH0/CONC-PARTS");
        StructuredAttemptResultView submitted = submit(learner, question,
                List.of("part-a", "part-b"));
        List<UUID> answerIds = answerIds(submitted);

        // Both transactions begin from the same PENDING attempt. The second completion
        // must not lose the settled transition merely because the first transaction
        // observed the other part as PENDING.
        ConcurrentResults results = runConcurrently(
                () -> teacherMarkingService.recordHumanMark(
                        answerIds.get(0), UUID.randomUUID(), 1, Map.of(), "part-a"),
                () -> teacherMarkingService.recordHumanMark(
                        answerIds.get(1), UUID.randomUUID(), 1, Map.of(), "part-b"));

        assertThat(results.failures()).isEmpty();
        SkillState state = skillStates
                .findByLearnerIdAndNodeId(learner, question.primaryTopicNodeId())
                .orElseThrow();
        assertThat(state.attempts()).as("exactly one evidence event after settlement")
                .isEqualTo(1);
        assertThat(state.correctCount()).isEqualTo(1);

        var attempt = attempts.findById(submitted.attemptId()).orElseThrow();
        assertThat(attempt.evidenceEmitted()).isTrue();
        assertThat(attempt.marksAwarded()).as("settled aggregate, not a part total")
                .isEqualTo(2);
        assertThat(answers.findByAttemptIdOrderByQuestionPartId(submitted.attemptId()))
                .allMatch(a -> a.markingState() == Answer.MarkingState.HUMAN_MARKED);
    }

    private ConcurrentResults runConcurrently(Callable<?> first, Callable<?> second)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        executor = Executors.newFixedThreadPool(2);

        Future<?> f1 = executor.submit(() -> invoke(first, ready, start, firstFailure));
        Future<?> f2 = executor.submit(() -> invoke(second, ready, start, secondFailure));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        await(f1);
        await(f2);
        return new ConcurrentResults(firstFailure.get(), secondFailure.get());
    }

    private static void invoke(Callable<?> action, CountDownLatch ready,
                               CountDownLatch start, AtomicReference<Throwable> failure) {
        ready.countDown();
        try {
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            action.call();
        } catch (Throwable t) {
            failure.set(t);
        }
    }

    private static void await(Future<?> future) throws Exception {
        try {
            future.get(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("concurrent marking task failed", e.getCause());
        }
    }

    private UUID newLearner() {
        return authService.register(new RegisterRequest(
                "it-concurrency-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@syllabai.test",
                "ItLearner123!", "Concurrency Learner")).user().id();
    }

    private Question validatedQuestion(String paperCode) {
        return validatedQuestion(paperCode, false);
    }

    private Question validatedTwoPartQuestion(String paperCode) {
        return validatedQuestion(paperCode, true);
    }

    private Question validatedQuestion(String paperCode, boolean twoParts) {
        PastPaperDraftDto.PartDraft first = new PastPaperDraftDto.PartDraft(
                "a", "Part a prompt", "State", 1, 0.5);
        List<PastPaperDraftDto.PartDraft> parts = twoParts
                ? List.of(first, new PastPaperDraftDto.PartDraft(
                        "b", "Part b prompt", "State", 1, 0.5))
                : List.of(first);
        List<PastPaperDraftDto.MarkPointDraft> points = twoParts
                ? List.of(
                        new PastPaperDraftDto.MarkPointDraft("1-a", 1, "part a", 1,
                                List.of(), 0.5),
                        new PastPaperDraftDto.MarkPointDraft("1-b", 1, "part b", 1,
                                List.of(), 0.5))
                : List.of(new PastPaperDraftDto.MarkPointDraft("1-a", 1,
                        "part a", 1, List.of(), 0.5));
        int marks = twoParts ? 2 : 1;

        PastPaperDraftDto draft = new PastPaperDraftDto(
                "1.0",
                new PastPaperDraftDto.PaperMeta("Edexcel", "IGCSE", "Chemistry",
                        "Paper 2C", "Concurrency-" + UUID.randomUUID().toString().substring(0, 8),
                        paperCode, "concurrency-qp", "concurrency-ms"),
                List.of(new PastPaperDraftDto.QuestionDraft("q1", "1", "Concurrency question",
                        "Explain", marks, "STRUCTURED", 1, 0.5, parts)),
                new PastPaperDraftDto.MarkSchemeDraft("1", "concurrency-ms", points),
                "it-concurrency-test", true);

        PastPaperIngestionService.IngestionSummary summary =
                ingestion.ingest(draft, UUID.randomUUID());
        assertThat(summary.questions()).isEqualTo(1);
        Question question = questions.findAllByOrderByDifficultyAsc().stream()
                .filter(q -> summary.paperId().equals(q.examPaperId()))
                .findFirst().orElseThrow();
        var version = questionVersions.findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        review.validateQuestionVersion(version.id());
        var scheme = markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id())
                .orElseThrow();
        List<ContentReviewService.PointCriteria> criteria = scheme.points().stream()
                .map(point -> new ContentReviewService.PointCriteria(
                        point.id(), point.acceptanceCriteria()))
                .toList();
        review.validateMarkScheme(scheme.id(), criteria);
        return question;
    }

    private StructuredAttemptResultView submit(UUID learner, Question question,
                                               List<String> texts) {
        var version = questionVersions.findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        List<PartAnswerRequest> requests = new java.util.ArrayList<>();
        for (int i = 0; i < version.parts().size(); i++) {
            requests.add(new PartAnswerRequest(version.parts().get(i).id(), texts.get(i)));
        }
        return assessment.submitStructured(learner,
                new StructuredSubmitRequest(question.id(), requests,
                        15000L, 3, false, true));
    }

    private List<UUID> answerIds(StructuredAttemptResultView submitted) {
        return answers.findByAttemptIdOrderByQuestionPartId(submitted.attemptId()).stream()
                .map(Answer::id).toList();
    }

    private record ConcurrentResults(Throwable first, Throwable second) {
        List<Throwable> failures() {
            return java.util.stream.Stream.of(first, second)
                    .filter(java.util.Objects::nonNull).toList();
        }
    }
}

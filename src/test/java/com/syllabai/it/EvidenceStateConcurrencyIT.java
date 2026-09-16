package com.syllabai.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.AssessmentService;
import com.syllabai.assessment.Attempt;
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
import com.syllabai.research.TelemetryEvent;
import com.syllabai.research.TelemetryEventRepository;
import com.syllabai.teacher.ContentReviewService;
import com.syllabai.teacher.TeacherMarkingService;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Database-backed concurrency verification for the structured evidence contract
 * (docs/EVIDENCE_STATE_TRANSITION_HARDENING.md — the CONCURRENCY GAP).
 *
 * <p>Exercises REAL Spring transactions (one @Transactional service call per
 * worker thread, no mocks, no simplified concurrency model) against real
 * Postgres via the repo's standard Testcontainers setup. The invariant under
 * test is stronger than publisher-level idempotence:</p>
 *
 * <blockquote>one settled attempt must produce exactly one
 * {@code AssessmentEvidenceRecordedEvent} — committed as telemetry
 * {@code ATTEMPT_SUBMITTED}(+{@code BKT_UPDATED} per topic node) — and exactly
 * one learner-model observation, regardless of concurrent authoritative
 * marking order, retry, or which transaction wins.</blockquote>
 *
 * <p>Scenarios: (A) same-answer concurrent marks; (B) different-part
 * concurrent marks, repeated rounds; (C) out-of-order completion (reverse part
 * order, sequential); (D) duplicate/retry submission before and after
 * settlement. Assertions are made against the DATABASE (telemetry event rows,
 * learner state, attempt/answer rows), never against return values alone.</p>
 *
 * <p>A worker that legitimately loses an optimistic-lock/transaction race is
 * NOT a test failure per se — the test asserts the resulting committed state
 * remains correct. Runs in CI where Docker exists; skipped locally
 * otherwise.</p>
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
    @Autowired private TelemetryEventRepository telemetry;

    private ExecutorService executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Scenario A — two transactions concurrently mark the SAME answer
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("A: same-answer concurrent marks commit evidence exactly once "
            + "(a legitimately lost race is acceptable, duplicate evidence is not)")
    void sameAnswerConcurrentMarksEmitEvidenceExactlyOnce() throws Exception {
        Question question = validatedQuestion("4CH0/CONC-SAME", false);
        List<String> observed = new ArrayList<>();
        final int rounds = 6;

        for (int round = 1; round <= rounds; round++) {
            UUID learner = newLearner();
            StructuredAttemptResultView submitted = submit(learner, question, List.of("answer"));
            UUID answerId = answerIds(submitted).get(0);

            RaceOutcome race = runConcurrently(
                    () -> teacherMarkingService.recordHumanMark(
                            answerId, UUID.randomUUID(), 1, Map.of(), "concurrent-a"),
                    () -> teacherMarkingService.recordHumanMark(
                            answerId, UUID.randomUUID(), 1, Map.of(), "concurrent-b"));
            assertOverlapped(race, "A round " + round);

            long losers = race.failureCount();
            if (losers > 0) {
                assertThat(losers).as("A round %d: at most one worker may lose the race", round)
                        .isLessThanOrEqualTo(1);
                assertThat(isLegitimateConflict(race.firstFailure(), race.secondFailure()))
                        .as("A round %d: the losing worker failed on a transaction conflict, "
                                + "not on an unrelated error", round).isTrue();
            }
            observed.add(roundObservation("A", round, learner, submitted.attemptId(), race));

            // A retry/override AFTER the race must remain evidence-idempotent.
            teacherMarkingService.recordHumanMark(
                    answerId, UUID.randomUUID(), 1, Map.of(), "post-race-retry");
        }

        for (int i = 0; i < observed.size(); i++) {
            int round = i + 1;
            UUID learner = learners.get(i);
            UUID attemptId = attemptIds.get(i);
            long evidence = evidenceEvents(learner, attemptId);
            long bkt = bktEvents(learner, attemptId);
            assertThat(evidence)
                    .as("A round %d/%d: evidence events for attempt %s — observed %s",
                            round, rounds, attemptId, String.join(" | ", observed))
                    .isEqualTo(1);
            assertThat(bkt)
                    .as("A round %d/%d: BKT_UPDATED telemetry rows for attempt %s", round, rounds,
                            attemptId)
                    .isEqualTo(1);
            SkillState state = skillStates
                    .findByLearnerIdAndNodeId(learner, question.primaryTopicNodeId()).orElseThrow();
            assertThat(state.attempts())
                    .as("A round %d: exactly one learner-model observation per attempt", round)
                    .isEqualTo(1);
            assertThat(state.correctCount()).isEqualTo(1);
            Attempt attempt = attempts.findById(attemptId).orElseThrow();
            assertThat(attempt.evidenceEmitted()).isTrue();
            assertThat(attempt.marksAwarded()).isEqualTo(1);
            assertThat(answers.findByAttemptIdOrderByQuestionPartId(attemptId))
                    .allSatisfy(a -> {
                        assertThat(a.markingState()).isIn(Answer.MarkingState.HUMAN_MARKED,
                                Answer.MarkingState.OVERRIDDEN);
                        assertThat(a.marksAwarded()).isEqualTo(1);
                    });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Scenario B — concurrent marks on DIFFERENT parts of one attempt
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("B: different-part concurrent marks settle the attempt and emit "
            + "exactly one final-aggregate evidence event (no lost evidence)")
    void differentPartConcurrentMarksEmitOneFinalAggregate() throws Exception {
        Question question = validatedQuestion("4CH0/CONC-PARTS", true);
        List<String> observed = new ArrayList<>();
        final int rounds = 8;

        for (int round = 1; round <= rounds; round++) {
            UUID learner = newLearner();
            StructuredAttemptResultView submitted = submit(learner, question,
                    List.of("part-a", "part-b"));
            List<UUID> answerIds = answerIds(submitted);

            // Both transactions begin from the same PENDING attempt; each may
            // initially observe the OTHER part as PENDING. The completing
            // transaction must still settle the attempt and fire evidence with
            // the FINAL aggregate — never a partial total, never nothing.
            RaceOutcome race = runConcurrently(
                    () -> teacherMarkingService.recordHumanMark(
                            answerIds.get(0), UUID.randomUUID(), 1, Map.of(), "part-a"),
                    () -> teacherMarkingService.recordHumanMark(
                            answerIds.get(1), UUID.randomUUID(), 1, Map.of(), "part-b"));
            assertOverlapped(race, "B round " + round);
            observed.add(roundObservation("B", round, learner, submitted.attemptId(), race));
        }

        for (int i = 0; i < observed.size(); i++) {
            int round = i + 1;
            UUID learner = learners.get(i);
            UUID attemptId = attemptIds.get(i);
            long evidence = evidenceEvents(learner, attemptId);
            assertThat(evidence)
                    .as("B round %d/%d: evidence events for settled attempt %s (lost-evidence "
                            + "check) — observed %s", round, rounds, attemptId,
                            String.join(" | ", observed))
                    .isEqualTo(1);
            Attempt attempt = attempts.findById(attemptId).orElseThrow();
            assertThat(attempt.evidenceEmitted())
                    .as("B round %d: attempt settled flag", round).isTrue();
            assertThat(attempt.marksAwarded())
                    .as("B round %d: settled aggregate equals the sum of settled parts",
                            round).isEqualTo(2);
            assertThat(attempt.correct()).isTrue();
            SkillState state = skillStates
                    .findByLearnerIdAndNodeId(learner, question.primaryTopicNodeId())
                    .orElse(null);
            assertThat(state).as("B round %d: learner state projected", round).isNotNull();
            assertThat(state.attempts()).isEqualTo(1);
            assertThat(state.correctCount()).isEqualTo(1);
            assertThat(answers.findByAttemptIdOrderByQuestionPartId(attemptId))
                    .allMatch(a -> a.markingState() == Answer.MarkingState.HUMAN_MARKED);
            assertThat(evidenceEventsPayload(learner, attemptId))
                    .allSatisfy(p -> {
                        assertThat(((Number) p.get("marksAwarded")).intValue())
                                .as("B round %d: evidence carries the final aggregate", round)
                                .isEqualTo(2);
                        assertThat(p.get("correctness")).isEqualTo(true);
                    });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Scenario C — out-of-order completion (part b settles before part a)
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("C: reverse-order completion — zero evidence before settlement, "
            + "final aggregate after, deterministic BKT posterior")
    void outOfOrderCompletionSettlesOnFinalAggregateNotArrivalOrder() {
        UUID learner = newLearner();
        Question question = validatedQuestion("4CH0/CONC-ORDER", true);
        StructuredAttemptResultView submitted = submit(learner, question,
                List.of("part-a", "part-b"));
        List<UUID> answerIds = answerIds(submitted);
        UUID partB = answerIds.get(1);
        UUID partA = answerIds.get(0);

        // part b completes FIRST (opposite of the logical part order)
        teacherMarkingService.recordHumanMark(partB, UUID.randomUUID(), 1, Map.of(), "b-first");

        // BEFORE settlement: no evidence, no projection, no settled flag
        assertThat(evidenceEvents(learner, submitted.attemptId()))
                .as("C: zero evidence events before settlement").isZero();
        assertThat(bktEvents(learner, submitted.attemptId()))
                .as("C: zero BKT observations before settlement").isZero();
        assertThat(skillStates.findByLearnerIdAndNodeId(learner,
                question.primaryTopicNodeId()))
                .as("C: no learner-state projection from a partial attempt").isEmpty();
        Attempt partial = attempts.findById(submitted.attemptId()).orElseThrow();
        assertThat(partial.evidenceEmitted()).isFalse();
        assertThat(partial.marksAwarded())
                .as("C: pre-settlement attempt carries the partial total (documented)")
                .isEqualTo(1);

        // part a completes LAST — the completing mark settles and fires ONCE
        teacherMarkingService.recordHumanMark(partA, UUID.randomUUID(), 1, Map.of(), "a-last");

        assertThat(evidenceEvents(learner, submitted.attemptId()))
                .as("C: exactly one evidence event after settlement").isEqualTo(1);
        Attempt settled = attempts.findById(submitted.attemptId()).orElseThrow();
        assertThat(settled.evidenceEmitted()).isTrue();
        assertThat(settled.marksAwarded())
                .as("C: final aggregate regardless of arrival order").isEqualTo(2);
        assertThat(settled.correct()).isTrue();

        // evidence payload references the right attempt/learner/topic and the
        // FINAL aggregate (not part b's partial 1)
        Map<String, Object> payload = evidenceEventsPayload(learner,
                submitted.attemptId()).get(0);
        assertThat(payload.get("attemptId")).isEqualTo(submitted.attemptId().toString());
        assertThat(payload.get("questionId")).isEqualTo(question.id().toString());
        assertThat(((Number) payload.get("marksTotal")).intValue()).isEqualTo(2);
        assertThat(((Number) payload.get("marksAwarded")).intValue()).isEqualTo(2);
        assertThat(payload.get("correctness")).isEqualTo(true);
        assertThat(payload.get("topicNodeIds"))
                .asList().contains(question.primaryTopicNodeId().toString());

        // deterministic learner-state projection: BKT(l0=0.1, slip=0.1, guess=0.25,
        // T=0.1) after ONE correct observation = 5/14 (pinned by BktEngineTest;
        // guards against partial-correctness contamination of counters/mastery)
        SkillState state = skillStates
                .findByLearnerIdAndNodeId(learner, question.primaryTopicNodeId()).orElseThrow();
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(state.correctCount()).isEqualTo(1);
        assertThat(state.mastery()).isCloseTo(5.0 / 14.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Scenario D — retry / duplicate submission around settlement
    // ─────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("D: duplicate/retried marks never double evidence or projection, "
            + "before or after settlement")
    void retryAndDuplicateSubmissionNeverDoublesEvidenceOrProjection() {
        UUID learner = newLearner();
        Question question = validatedQuestion("4CH0/CONC-RETRY", true);
        StructuredAttemptResultView submitted = submit(learner, question,
                List.of("part-a", "part-b"));
        List<UUID> answerIds = answerIds(submitted);
        UUID partA = answerIds.get(0);
        UUID partB = answerIds.get(1);

        // (1) client-retry of the SAME part mark before settlement: the
        // duplicate must not prematurely settle, double-count, or fire evidence
        teacherMarkingService.recordHumanMark(partA, UUID.randomUUID(), 1, Map.of(), "a");
        teacherMarkingService.recordHumanMark(partA, UUID.randomUUID(), 1, Map.of(), "a-retry");
        assertThat(evidenceEvents(learner, submitted.attemptId()))
                .as("D: no evidence from a duplicated partial mark").isZero();
        assertThat(attempts.findById(submitted.attemptId()).orElseThrow().marksAwarded())
                .as("D: duplicate partial mark does not double the partial total").isEqualTo(1);

        // (2) the completing mark fires exactly once with the final aggregate
        teacherMarkingService.recordHumanMark(partB, UUID.randomUUID(), 1, Map.of(), "b");
        assertThat(evidenceEvents(learner, submitted.attemptId()))
                .as("D: exactly one evidence event after settlement").isEqualTo(1);

        // (3) retries AFTER settlement are overrides: no second evidence, no
        // second projection, no stale intermediate total overwriting the final
        teacherMarkingService.recordHumanMark(partB, UUID.randomUUID(), 1, Map.of(), "b-retry");
        teacherMarkingService.recordHumanMark(partA, UUID.randomUUID(), 1, Map.of(), "a-retry-2");
        assertThat(evidenceEvents(learner, submitted.attemptId()))
                .as("D: post-settlement retries do not re-fire evidence").isEqualTo(1);
        assertThat(bktEvents(learner, submitted.attemptId()))
                .as("D: post-settlement retries do not re-project learner state").isEqualTo(1);
        SkillState state = skillStates
                .findByLearnerIdAndNodeId(learner, question.primaryTopicNodeId()).orElseThrow();
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(state.correctCount()).isEqualTo(1);
        assertThat(attempts.findById(submitted.attemptId()).orElseThrow().marksAwarded())
                .as("D: final aggregate survives post-settlement retries").isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────
    // race machinery
    // ─────────────────────────────────────────────────────────────────────

    private record Interval(long startNanos, long endNanos) {
    }

    private record RaceOutcome(Interval first, Interval second,
                               Throwable firstFailure, Throwable secondFailure) {
        long failureCount() {
            return (firstFailure == null ? 0 : 1) + (secondFailure == null ? 0 : 1);
        }
    }

    /** Runs both actions on separate threads released simultaneously by a latch. */
    private RaceOutcome runConcurrently(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<>();
        AtomicReference<Interval> firstInterval = new AtomicReference<>();
        AtomicReference<Interval> secondInterval = new AtomicReference<>();
        executor = Executors.newFixedThreadPool(2);

        Future<?> f1 = executor.submit(() -> invokeTimed(first, ready, start,
                firstFailure, firstInterval));
        Future<?> f2 = executor.submit(() -> invokeTimed(second, ready, start,
                secondFailure, secondInterval));

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        await(f1);
        await(f2);
        return new RaceOutcome(firstInterval.get(), secondInterval.get(),
                firstFailure.get(), secondFailure.get());
    }

    private static void invokeTimed(Callable<?> action, CountDownLatch ready,
                                    CountDownLatch start,
                                    AtomicReference<Throwable> failure,
                                    AtomicReference<Interval> interval) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                failure.set(new IllegalStateException("start latch never opened"));
                return;
            }
        } catch (InterruptedException e) {
            failure.set(e);
            return;
        }
        long begin = System.nanoTime();
        try {
            action.call();
        } catch (Throwable t) {
            failure.set(t);
        }
        interval.set(new Interval(begin, System.nanoTime()));
    }

    private static void await(Future<?> future) throws Exception {
        future.get(30, TimeUnit.SECONDS);
    }

    /** The race only demonstrates anything if the two transactions actually overlapped. */
    private static void assertOverlapped(RaceOutcome race, String what) {
        assertThat(race.first()).as(what + ": first worker recorded its interval").isNotNull();
        assertThat(race.second()).as(what + ": second worker recorded its interval").isNotNull();
        boolean overlapped = race.first().startNanos() < race.second().endNanos()
                && race.second().startNanos() < race.first().endNanos();
        assertThat(overlapped)
                .as("%s: transactions actually overlapped (w1=[%d..%d] ns, w2=[%d..%d] ns) — "
                        + "without overlap the race proves nothing", what,
                        race.first().startNanos(), race.first().endNanos(),
                        race.second().startNanos(), race.second().endNanos())
                .isTrue();
    }

    /**
     * A worker that lost a transaction conflict is legitimate (the mission's
     * documented semantics): unique-constraint / optimistic-lock / serialization
     * failures mean Postgres itself refused the double transition. Anything else
     * (NPE, assertion, illegal state) is a real failure.
     */
    private static boolean isLegitimateConflict(Throwable first, Throwable second) {
        Throwable t = first != null ? first : second;
        while (t != null) {
            String name = t.getClass().getName();
            String msg = String.valueOf(t.getMessage());
            if (name.contains("DataIntegrityViolation")
                    || name.contains("OptimisticLock")
                    || name.contains("ConstraintViolation")
                    || name.contains("PSQLException")
                    || msg.contains("duplicate key")
                    || msg.contains("serialized access")
                    || msg.contains("deadlock detected")
                    || msg.contains("uq_")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────
    // database observation helpers
    // ─────────────────────────────────────────────────────────────────────

    private long evidenceEvents(UUID learnerId, UUID attemptId) {
        return evidenceEventsPayload(learnerId, attemptId).size();
    }

    private List<Map<String, Object>> evidenceEventsPayload(UUID learnerId, UUID attemptId) {
        List<Map<String, Object>> payloads = new ArrayList<>();
        for (TelemetryEvent e : telemetry.findByLearnerIdOrderByOccurredAtDesc(
                learnerId, Pageable.unpaged())) {
            if (e.type() == TelemetryEvent.Type.ATTEMPT_SUBMITTED
                    && attemptId.toString().equals(e.payload().get("attemptId"))) {
                payloads.add(e.payload());
            }
        }
        return payloads;
    }

    private long bktEvents(UUID learnerId, UUID attemptId) {
        return telemetry.findByLearnerIdOrderByOccurredAtDesc(learnerId, Pageable.unpaged())
                .stream()
                .filter(e -> e.type() == TelemetryEvent.Type.BKT_UPDATED)
                .filter(e -> attemptId.toString().equals(e.payload().get("attemptId")))
                .count();
    }

    private String roundObservation(String scenario, int round, UUID learner, UUID attemptId,
                                    RaceOutcome race) {
        Attempt attempt = attempts.findById(attemptId).orElse(null);
        Optional<SkillState> state = skillStates
                .findByLearnerIdAndNodeId(learner, lastPrimaryNode);
        return String.format(
                "%s-r%d[evidence=%d,bkt=%d,marks=%s,emitted=%s,skillAttempts=%s,failures=%d]",
                scenario, round, evidenceEvents(learner, attemptId), bktEvents(learner, attemptId),
                attempt == null ? "?" : attempt.marksAwarded(),
                attempt == null ? "?" : attempt.evidenceEmitted(),
                state.map(SkillState::attempts).map(Object::toString).orElse("absent"),
                race.failureCount());
    }

    // ─────────────────────────────────────────────────────────────────────
    // fixtures
    // ─────────────────────────────────────────────────────────────────────

    private UUID lastPrimaryNode;
    private final List<UUID> learners = new ArrayList<>();
    private final List<UUID> attemptIds = new ArrayList<>();

    private UUID newLearner() {
        UUID id = authService.register(new RegisterRequest(
                "it-concurrency-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@syllabai.test",
                "ItLearner123!", "Concurrency Learner")).user().id();
        learners.add(id);
        return id;
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
        lastPrimaryNode = question.primaryTopicNodeId();
        return question;
    }

    private StructuredAttemptResultView submit(UUID learner, Question question,
                                               List<String> texts) {
        var version = questionVersions.findByQuestionIdOrderByVersionDesc(question.id()).get(0);
        List<PartAnswerRequest> requests = new ArrayList<>();
        for (int i = 0; i < version.parts().size(); i++) {
            requests.add(new PartAnswerRequest(version.parts().get(i).id(), texts.get(i)));
        }
        StructuredAttemptResultView submitted = assessment.submitStructured(learner,
                new StructuredSubmitRequest(question.id(), requests,
                        15000L, 3, false, true));
        attemptIds.add(submitted.attemptId());
        return submitted;
    }

    private List<UUID> answerIds(StructuredAttemptResultView submitted) {
        return answers.findByAttemptIdOrderByQuestionPartId(submitted.attemptId()).stream()
                .map(Answer::id).toList();
    }
}

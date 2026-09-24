package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.identity.UserRepository;
import com.syllabai.shared.BadRequestException;
import com.syllabai.smartmark.HumanMarkRepository;
import com.syllabai.smartmark.SmartMarkResult;
import com.syllabai.smartmark.SmartMarkResultRepository;
import com.syllabai.smartmark.SmartMarkService;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Sprint 2 §6/§7 unit semantics: the marking queue's DETERMINISTIC ordering
 * (paper groups contiguous, oldest-waiting paper first, attempt age then part
 * label within an attempt), the mark→next chain, honest throughput counts, and
 * the bounded Smart Mark batch (skip semantics, partial failure, bounds,
 * dedup). Ordering must be stable across calls; metrics must never invent
 * numbers; the batch must never re-mark a non-PENDING answer.
 */
class TeacherMarkingQueueServiceTest {

    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final SmartMarkResultRepository smartMarkResults =
            mock(SmartMarkResultRepository.class);
    private final SmartMarkService smartMarkService = mock(SmartMarkService.class);
    private final HumanMarkRepository humanMarks = mock(HumanMarkRepository.class);
    private final UserRepository users = mock(UserRepository.class);

    private final TeacherMarkingQueueService service = new TeacherMarkingQueueService(
            answers, examPapers, smartMarkResults, smartMarkService, humanMarks, users);

    private static final Instant BASE = Instant.now().minus(2, ChronoUnit.HOURS);

    // ---- test fixtures ----

    /** one paper + its question + a validated version parts can attach to */
    private record PaperFix(ExamPaper paper, Question question, QuestionVersion version) {
    }

    private PaperFix paperFix(UUID id, String title) {
        ExamPaper paper = new ExamPaper(UUID.randomUUID(), title, "Edexcel", "IGCSE",
                "Paper 1", "June 2019", "4CH0/1F-" + id.toString().substring(0, 4),
                "qp-doc", "ms-doc", ExamPaper.Provenance.PAST_PAPER, "test", null);
        TestIds.withId(paper, id);
        Question question = new Question("q-" + UUID.randomUUID(),
                Question.Type.STRUCTURED, "stem", 2, 3, 120, "Explain",
                UUID.randomUUID(), Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        question.attachToPaper(id);
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 2, 3, 120,
                "Explain", QuestionVersion.ValidationState.VALIDATED, "doc", 0.9, "test");
        TestIds.withId(version, UUID.randomUUID());
        return new PaperFix(paper, question, version);
    }

    /** a PENDING answer on the fix's paper; shared attempt when one is given */
    private Answer pendingAnswer(PaperFix fix, Attempt sharedAttempt,
                                 Instant attemptAt, String partLabel) {
        QuestionPart part = new QuestionPart(fix.version(), partLabel, "prompt",
                "State", 2, 0);
        TestIds.withId(part, UUID.randomUUID());
        fix.version().addPart(part);

        Attempt attempt = sharedAttempt;
        if (attempt == null) {
            attempt = new Attempt(UUID.randomUUID(), fix.question(), null, false, null,
                    5000L, 4, false, false, "test");
            TestIds.withId(attempt, UUID.randomUUID());
            attempt.beginMarking();
            setField(attempt, "createdAt", attemptAt);
        }
        Answer answer = new Answer(attempt, part, "some answer text");
        TestIds.withId(answer, UUID.randomUUID());
        return answer;
    }

    private void stageQueue(List<Answer> queue, PaperFix... fixes) {
        when(answers.findByMarkingState(Answer.MarkingState.PENDING)).thenReturn(queue);
        List<ExamPaper> papers = new ArrayList<>();
        for (PaperFix f : fixes) {
            papers.add(f.paper());
        }
        lenient().when(examPapers.findAllById(anyCollection())).thenReturn(papers);
        lenient().when(smartMarkResults.findByAnswerIdsOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of());
        lenient().when(humanMarks.findByAnswerIdsOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of());
        lenient().when(users.findAllById(anyCollection())).thenReturn(List.of());
    }

    /** reflection: test-only field injection for @PrePersist-managed columns */
    private static void setField(Object target, String name, Object value) {
        try {
            Class<?> current = target.getClass();
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name + " on " + target.getClass());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** a real, persisted-shape Smart Mark result (no mock: answerId must work) */
    private static SmartMarkResult smartResult(Answer answer, boolean accepted,
                                               int marks, String failureReason) {
        return new SmartMarkResult(answer, "test-model", marks, 0.9, accepted,
                List.of(), failureReason, "raw");
    }

    // ---- ordering (§7) ----

    @Test
    @DisplayName("queue groups answers by paper, oldest-waiting paper first, groups contiguous")
    void orderingGroupsByPaperOldestWaitingFirst() {
        PaperFix fixA = paperFix(UUID.randomUUID(), "Paper A");
        PaperFix fixB = paperFix(UUID.randomUUID(), "Paper B");
        // paperB holds the OLDEST attempt → its group must come first
        Answer bOld = pendingAnswer(fixB, null, BASE.minus(90, ChronoUnit.MINUTES), "a");
        Answer aNewer = pendingAnswer(fixA, null, BASE, "b");
        Answer bNewer = pendingAnswer(fixB, null, BASE.minus(30, ChronoUnit.MINUTES), "b");
        // repository returns them in arbitrary order
        stageQueue(List.of(aNewer, bNewer, bOld), fixA, fixB);

        var view = service.markingQueue(Answer.MarkingState.PENDING);

        assertThat(view.items()).hasSize(3);
        // paper B group first (oldest waiting), fully contiguous
        assertThat(view.items().get(0).answer().answerId()).isEqualTo(bOld.id());
        assertThat(view.items().get(1).answer().answerId()).isEqualTo(bNewer.id());
        assertThat(view.items().get(2).answer().answerId()).isEqualTo(aNewer.id());
        assertThat(view.groups()).hasSize(2);
        assertThat(view.groups().get(0).paperId()).isEqualTo(fixB.paper().id());
        assertThat(view.groups().get(0).count()).isEqualTo(2);
        assertThat(view.groups().get(0).oldestWaitingHours()).isNotNull();
        assertThat(view.groups().get(1).paperId()).isEqualTo(fixA.paper().id());
    }

    @Test
    @DisplayName("within a paper: attempt age first, then part label inside an attempt")
    void withinPaperAttemptAgeThenPartLabel() {
        PaperFix fix = paperFix(UUID.randomUUID(), "P");
        // an older attempt (part label z — age dominates the label)
        Answer older = pendingAnswer(fix, null, BASE.minus(60, ChronoUnit.MINUTES), "z");
        // one newer attempt with two parts: b created before a, but label a < b
        Attempt shared = null;
        Answer newerB = pendingAnswer(fix, null, BASE, "b");
        shared = newerB.attempt();
        Answer newerA = pendingAnswer(fix, shared, BASE, "a");
        stageQueue(List.of(newerA, newerB, older), fix);

        var view = service.markingQueue(Answer.MarkingState.PENDING);

        assertThat(view.items()).extracting(i -> i.answer().answerId())
                .containsExactly(older.id(), newerA.id(), newerB.id());
    }

    @Test
    @DisplayName("ordering is deterministic across repeated calls")
    void orderingDeterministicAcrossCalls() {
        PaperFix fix = paperFix(UUID.randomUUID(), "P");
        List<Answer> many = IntStream.range(0, 12)
                .mapToObj(i -> pendingAnswer(fix, null, BASE.plusSeconds(i * 60),
                        String.valueOf((char) ('a' + i % 5))))
                .toList();
        stageQueue(new ArrayList<>(many), fix);

        var first = service.markingQueue(Answer.MarkingState.PENDING);
        var second = service.markingQueue(Answer.MarkingState.PENDING);

        assertThat(first.items()).extracting(i -> i.answer().answerId())
                .containsExactlyElementsOf(
                        second.items().stream()
                                .map(i -> i.answer().answerId()).toList());
    }

    @Test
    @DisplayName("mark→next chain links the ordered items; last item points nowhere")
    void markNextChainLinksOrderedItems() {
        PaperFix fix = paperFix(UUID.randomUUID(), "P");
        Answer first = pendingAnswer(fix, null, BASE, "a");
        Answer second = pendingAnswer(fix, null, BASE.plusSeconds(60), "a");
        Answer third = pendingAnswer(fix, null, BASE.plusSeconds(120), "a");
        stageQueue(List.of(first, second, third), fix);

        var view = service.markingQueue(Answer.MarkingState.PENDING);

        assertThat(view.items().get(0).nextAnswerId()).isEqualTo(second.id());
        assertThat(view.items().get(1).nextAnswerId()).isEqualTo(third.id());
        assertThat(view.items().get(2).nextAnswerId()).isNull();
    }

    @Test
    @DisplayName("empty queue returns an honest empty view, not an error")
    void emptyQueueIsHonestEmpty() {
        stageQueue(List.of());

        var view = service.markingQueue(Answer.MarkingState.PENDING);

        assertThat(view.items()).isEmpty();
        assertThat(view.groups()).isEmpty();
        assertThat(view.state()).isEqualTo("PENDING");
    }

    // ---- throughput (§6) ----

    @Test
    @DisplayName("throughput reports counts of what happened; empty is zero, not invented")
    void throughputCountsAreHonest() {
        when(answers.countGroupedByMarkingState()).thenReturn(List.<Object[]>of(
                new Object[]{Answer.MarkingState.PENDING, 243L},
                new Object[]{Answer.MarkingState.HUMAN_MARKED, 7L}));
        when(humanMarks.countSince(any(Instant.class))).thenReturn(7L);
        when(answers.findByMarkingState(Answer.MarkingState.PENDING))
                .thenReturn(List.of());

        var view = service.throughput();

        assertThat(view.answersByState()).containsEntry("PENDING", 243L)
                .containsEntry("HUMAN_MARKED", 7L)
                .containsEntry("SMART_MARKED", 0L)   // honest zero
                .containsEntry("OVERRIDDEN", 0L);
        assertThat(view.oldestPendingAt()).isNull();
        assertThat(view.oldestPendingHours()).isNull();
        assertThat(view.pendingByPaper()).isEmpty();
    }

    @Test
    @DisplayName("throughput pending-by-paper leaders are bounded and deterministic")
    void throughputLeadersBounded() {
        when(answers.countGroupedByMarkingState()).thenReturn(List.of());
        when(humanMarks.countSince(any(Instant.class))).thenReturn(0L);
        // 7 papers with increasing pending counts
        List<Answer> pending = new ArrayList<>();
        List<ExamPaper> papers = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            PaperFix fix = paperFix(UUID.randomUUID(), "Paper " + i);
            papers.add(fix.paper());
            for (int j = 0; j <= i; j++) {
                pending.add(pendingAnswer(fix, null, BASE.plusSeconds(j), "a"));
            }
        }
        when(answers.findByMarkingState(Answer.MarkingState.PENDING)).thenReturn(pending);
        when(examPapers.findAllById(anyCollection())).thenReturn(papers);

        var view = service.throughput();

        assertThat(view.pendingByPaper()).hasSize(5);   // bounded to leaders
        // most pending first
        assertThat(view.pendingByPaper().get(0).pending()).isEqualTo(7);
        assertThat(view.oldestPendingAt()).isNotNull();
        assertThat(view.oldestPendingHours()).isNotNull();
    }

    // ---- Smart Mark batch (§6) ----

    @Test
    @DisplayName("batch skips non-PENDING answers instead of re-marking them")
    void batchSkipsNonPending() {
        PaperFix fix = paperFix(UUID.randomUUID(), "P");
        Answer pending = pendingAnswer(fix, null, BASE, "a");
        Answer done = pendingAnswer(fix, null, BASE, "b");
        done.humanMarked(1);

        when(answers.findAllById(anyCollection())).thenReturn(List.of(pending, done));
        SmartMarkResult ok = smartResult(pending, true, 2, null);
        when(smartMarkService.markAnswer(pending.id())).thenReturn(ok);

        var view = service.smartMarkBatch(List.of(pending.id(), done.id()));

        assertThat(view.items()).hasSize(2);
        assertThat(view.items().get(0).outcome()).isEqualTo("MARKED");
        assertThat(view.items().get(1).outcome()).isEqualTo("SKIPPED_ALREADY_MARKED");
        verify(smartMarkService, never()).markAnswer(done.id());
        assertThat(view.marked()).isEqualTo(1);
        assertThat(view.skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("batch reports per-item failure and continues (partial success preserved)")
    void batchPartialFailureContinues() {
        PaperFix fix = paperFix(UUID.randomUUID(), "P");
        Answer bad = pendingAnswer(fix, null, BASE, "a");
        Answer good = pendingAnswer(fix, null, BASE.plusSeconds(60), "b");

        when(answers.findAllById(anyCollection())).thenReturn(List.of(bad, good));
        when(smartMarkService.markAnswer(bad.id()))
                .thenThrow(new com.syllabai.shared.NotFoundException("mark scheme", "x"));
        SmartMarkResult ok = smartResult(good, true, 1, null);
        when(smartMarkService.markAnswer(good.id())).thenReturn(ok);

        var view = service.smartMarkBatch(List.of(bad.id(), good.id()));

        assertThat(view.marked()).isEqualTo(1);
        assertThat(view.failed()).isEqualTo(1);
        assertThat(view.items().get(0).outcome()).isEqualTo("FAILED");
        assertThat(view.items().get(0).marksAwarded()).isNull();
        assertThat(view.items().get(1).outcome()).isEqualTo("MARKED");
        assertThat(view.items().get(1).marksAwarded()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed validation run is an honest FAILED item with reason, no marks")
    void batchFailedValidationReported() {
        PaperFix fix = paperFix(UUID.randomUUID(), "P");
        Answer answer = pendingAnswer(fix, null, BASE, "a");
        when(answers.findAllById(anyCollection())).thenReturn(List.of(answer));
        SmartMarkResult failed = smartResult(answer, false, 0, "PROVIDER_UNAVAILABLE");
        when(smartMarkService.markAnswer(answer.id())).thenReturn(failed);

        var view = service.smartMarkBatch(List.of(answer.id()));

        assertThat(view.items().get(0).outcome()).isEqualTo("FAILED");
        assertThat(view.items().get(0).reason()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(view.items().get(0).marksAwarded()).isNull();
    }

    @Test
    @DisplayName("batch bounds: >50 ids rejected, empty rejected")
    void batchBoundsEnforced() {
        List<UUID> tooMany = IntStream.range(0, 51)
                .mapToObj(i -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> service.smartMarkBatch(tooMany))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("batch too large");
        assertThatThrownBy(() -> service.smartMarkBatch(List.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("batch deduplicates repeated ids — one pipeline run per answer")
    void batchDeduplicates() {
        PaperFix fix = paperFix(UUID.randomUUID(), "P");
        Answer answer = pendingAnswer(fix, null, BASE, "a");
        when(answers.findAllById(anyCollection())).thenReturn(List.of(answer));
        SmartMarkResult ok = smartResult(answer, true, 2, null);
        when(smartMarkService.markAnswer(answer.id())).thenReturn(ok);

        var view = service.smartMarkBatch(List.of(answer.id(), answer.id()));

        assertThat(view.requested()).isEqualTo(1);
        verify(smartMarkService, times(1)).markAnswer(answer.id());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> captor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(answers).findAllById(captor.capture());
        assertThat(captor.getValue()).containsExactly(answer.id());
    }

    @Test
    @DisplayName("unknown id in batch is an honest FAILED item, not a silent drop")
    void batchUnknownIdReported() {
        UUID unknown = UUID.randomUUID();
        when(answers.findAllById(anyCollection())).thenReturn(List.of());

        var view = service.smartMarkBatch(List.of(unknown));

        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).outcome()).isEqualTo("FAILED");
        assertThat(view.items().get(0).reason()).contains("not found");
    }

    // ---- question-bank (null examPaperId) answers: the unfiled group (regression) ----
    // Production defect (session 118): a SMART_MARKED answer on a question-bank
    // (SME) question — examPaperId null BY DESIGN — crashed queue-v2 with a 500:
    // the nullable id flowed into findAllById (rejects null elements) and the
    // group-sort tie-break called compareTo on it. Both must stay null-safe.

    /** a question deliberately NOT attached to any paper: examPaperId stays null */
    private record BankFix(Question question, QuestionVersion version) {
    }

    private BankFix bankFix() {
        Question question = new Question("q-" + UUID.randomUUID(),
                Question.Type.STRUCTURED, "stem", 2, 3, 120, "Explain",
                UUID.randomUUID(), Question.Provenance.TEACHER_AUTHORED);
        TestIds.withId(question, UUID.randomUUID());
        // deliberately NO attachToPaper — the question-bank case
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 2, 3, 120,
                "Explain", QuestionVersion.ValidationState.VALIDATED, "doc", 0.9, "test");
        TestIds.withId(version, UUID.randomUUID());
        return new BankFix(question, version);
    }

    private Answer bankAnswer(BankFix fix, Instant attemptAt, String partLabel) {
        QuestionPart part = new QuestionPart(fix.version(), partLabel, "prompt",
                "State", 1, 0);
        TestIds.withId(part, UUID.randomUUID());
        fix.version().addPart(part);
        Attempt attempt = new Attempt(UUID.randomUUID(), fix.question(), null, false, null,
                5000L, 4, false, false, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        attempt.beginMarking();
        setField(attempt, "createdAt", attemptAt);
        Answer answer = new Answer(attempt, part, "bank answer text");
        TestIds.withId(answer, UUID.randomUUID());
        return answer;
    }

    @SuppressWarnings("unchecked")
    private org.mockito.ArgumentCaptor<Collection<UUID>> paperIdsCaptor() {
        return (org.mockito.ArgumentCaptor<Collection<UUID>>) (Object)
                org.mockito.ArgumentCaptor.forClass(Collection.class);
    }

    @Test
    @DisplayName("SMART_MARKED queue with question-bank answers: unfiled group renders, paper lookup never sees a null id")
    void markingQueueNullPaperAnswersGroupWithoutCrash() {
        PaperFix fix = paperFix(UUID.randomUUID(), "Chemistry Paper 1");
        BankFix bank = bankFix();
        Answer paperAnswer = pendingAnswer(fix, null, BASE, "a");
        paperAnswer.smartMarked(2);
        Answer bankA = bankAnswer(bank, BASE.minus(10, ChronoUnit.MINUTES), "a");
        bankA.smartMarked(1);
        Answer bankB = bankAnswer(bank, BASE.minus(5, ChronoUnit.MINUTES), "b");
        bankB.smartMarked(0);

        when(answers.findByMarkingState(Answer.MarkingState.SMART_MARKED))
                .thenReturn(List.of(bankA, bankB, paperAnswer));
        lenient().when(examPapers.findAllById(anyCollection())).thenReturn(List.of(fix.paper()));
        lenient().when(smartMarkResults.findByAnswerIdsOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of());
        lenient().when(humanMarks.findByAnswerIdsOrderByCreatedAtAsc(anyCollection()))
                .thenReturn(List.of());
        lenient().when(users.findAllById(anyCollection())).thenReturn(List.of());

        var view = service.markingQueue(Answer.MarkingState.SMART_MARKED);

        // the two bank answers form ONE unfiled group (null paper, null context)
        assertThat(view.groups()).hasSize(2);
        assertThat(view.groups()).anyMatch(g -> g.paperId() == null
                && g.paperTitle() == null && g.count() == 2);
        assertThat(view.groups()).anyMatch(g -> fix.paper().id().equals(g.paperId()));
        assertThat(view.items()).hasSize(3);
        // THE regression: the paper lookup must never receive a null id element
        var captor = paperIdsCaptor();
        verify(examPapers, times(1)).findAllById(captor.capture());
        assertThat(captor.getValue()).doesNotContainNull();
        assertThat(captor.getValue()).containsExactly(fix.paper().id());
    }

    @Test
    @DisplayName("throughput with question-bank pending answers: honest counts, no null id reaches the paper lookup")
    void throughputNullPaperAnswersCountWithoutCrash() {
        PaperFix fix = paperFix(UUID.randomUUID(), "Chemistry Paper 1");
        BankFix bank = bankFix();
        Answer paperAnswer = pendingAnswer(fix, null, BASE, "a");
        Answer bankAnswer = bankAnswer(bank, BASE.minus(1, ChronoUnit.MINUTES), "a");
        when(answers.countGroupedByMarkingState()).thenReturn(List.<Object[]>of(
                new Object[]{Answer.MarkingState.PENDING, 2L}));
        when(humanMarks.countSince(any(Instant.class))).thenReturn(0L);
        when(answers.findByMarkingState(Answer.MarkingState.PENDING))
                .thenReturn(List.of(paperAnswer, bankAnswer));
        when(examPapers.findAllById(anyCollection())).thenReturn(List.of(fix.paper()));

        var view = service.throughput();

        assertThat(view.answersByState()).containsEntry("PENDING", 2L);
        // the bank answer lands in the unfiled (null paperId) leaders bucket
        assertThat(view.pendingByPaper()).hasSize(2);
        var captor = paperIdsCaptor();
        verify(examPapers, times(1)).findAllById(captor.capture());
        assertThat(captor.getValue()).doesNotContainNull();
        assertThat(captor.getValue()).containsExactly(fix.paper().id());
    }
}

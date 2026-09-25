package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.identity.Role;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.smartmark.HumanMark;
import com.syllabai.smartmark.HumanMarkRepository;
import com.syllabai.smartmark.SmartMarkAgreementEvaluation;
import com.syllabai.smartmark.SmartMarkAgreementEvaluationRepository;
import com.syllabai.smartmark.SmartMarkResult;
import com.syllabai.smartmark.SmartMarkResultRepository;
import com.syllabai.smartmark.SmartMarkService;
import com.syllabai.teacher.dto.TeacherViews;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * T-029: the marking queue is a self-contained teacher read model — the server
 * composes learner display names (one batched identity lookup), so the queue
 * and the answer detail never need a client-side join.
 */
class TeacherMarkingControllerTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID MARKER = UUID.randomUUID();

    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final SmartMarkService smartMarkService = mock(SmartMarkService.class);
    private final TeacherMarkingService teacherMarkingService = mock(TeacherMarkingService.class);
    private final TeacherMarkingQueueService markingQueueService =
            mock(TeacherMarkingQueueService.class);
    private final SmartMarkResultRepository smartMarkResults = mock(SmartMarkResultRepository.class);
    private final HumanMarkRepository humanMarks = mock(HumanMarkRepository.class);
    private final SmartMarkAgreementEvaluationRepository agreementEvaluations =
            mock(SmartMarkAgreementEvaluationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final com.syllabai.assessment.ExamPaperRepository examPapers =
            mock(com.syllabai.assessment.ExamPaperRepository.class);

    private final TeacherMarkingController controller = new TeacherMarkingController(
            answers, smartMarkService, teacherMarkingService, markingQueueService,
            smartMarkResults, humanMarks, agreementEvaluations, users, examPapers);

    private final Question question;
    private final QuestionPart part;
    private final Attempt attempt;
    private final Answer answer;

    TeacherMarkingControllerTest() {
        question = new Question("q-1", Question.Type.STRUCTURED, "stem", 2, 3, 120,
                "Explain", UUID.randomUUID(), Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 2, 3, 120, "Explain",
                QuestionVersion.ValidationState.VALIDATED, "doc", 0.9, "test");
        TestIds.withId(version, UUID.randomUUID());
        part = new QuestionPart(version, "a", "part a", "State", 2, 0);
        TestIds.withId(part, UUID.randomUUID());
        version.addPart(part);

        attempt = new Attempt(LEARNER, question, null, false, null,
                5000L, 4, false, false, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        attempt.beginMarking();
        answer = new Answer(attempt, part, "iron oxide and water");
        TestIds.withId(answer, UUID.randomUUID());

        User learner = new User("ada@example.com", "hash", "Ada Learner", Set.of(Role.STUDENT));
        TestIds.withId(learner, LEARNER);
        when(users.findAllById(anySet())).thenReturn(List.of(learner));
    }

    @Test
    @DisplayName("queue items carry the learner display name from one batched lookup")
    void queueCarriesLearnerNames() {
        when(answers.findByMarkingState(Answer.MarkingState.PENDING)).thenReturn(List.of(answer));

        List<TeacherViews.AnswerMarkingView> queue = controller.queue("PENDING");

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).learnerDisplayName()).isEqualTo("Ada Learner");
        assertThat(queue.get(0).learnerId()).isEqualTo(LEARNER);
        assertThat(queue.get(0).partLabel()).isEqualTo("a");
        assertThat(queue.get(0).partMarks()).isEqualTo(2);
        verify(users).findAllById(Set.of(LEARNER));
    }

    @Test
    @DisplayName("queue is case-insensitive on the state filter; unknown states are a 400 bad request")
    void queueStateFilter() {
        when(answers.findByMarkingState(Answer.MarkingState.PENDING)).thenReturn(List.of());
        assertThat(controller.queue("pending")).isEmpty();

        // C-9: an unknown filter value is malformed input, not a missing resource
        assertThatThrownBy(() -> controller.queue("NOT_A_STATE"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unknown marking state: NOT_A_STATE");
        verify(users, never()).findAllById(anySet());
    }

    @Test
    @DisplayName("empty queue skips the identity lookup entirely")
    void emptyQueueSkipsLookup() {
        when(answers.findByMarkingState(Answer.MarkingState.PENDING)).thenReturn(List.of());
        controller.queue("PENDING");
        verify(users, never()).findAllById(anySet());
    }

    @Test
    @DisplayName("answer detail composes learner name + latest smart + latest human marks")
    void answerDetail() {
        when(answers.findWithPartAndAttempt(answer.id())).thenReturn(Optional.of(answer));
        SmartMarkResult smart = new SmartMarkResult(answer, "test-model", 1, 0.8,
                true, List.of(Map.of("markPointId", "mp", "awarded", true)), null, "raw");
        when(smartMarkResults.findLatest(answer.id())).thenReturn(Optional.of(smart));
        HumanMark human = new HumanMark(answer, MARKER, 2, Map.of("mp", 1), "both points");
        when(humanMarks.findLatest(answer.id())).thenReturn(Optional.of(human));

        TeacherViews.AnswerMarkingView detail = controller.answer(answer.id());

        assertThat(detail.learnerDisplayName()).isEqualTo("Ada Learner");
        assertThat(detail.latestSmartMark().marksAwarded()).isEqualTo(1);
        assertThat(detail.latestSmartMark().modelId()).isEqualTo("test-model");
        assertThat(detail.latestHumanMark().marksAwarded()).isEqualTo(2);
        assertThat(detail.latestHumanMark().comments()).isEqualTo("both points");
    }

    @Test
    @DisplayName("unknown answer id fails loud with 404")
    void unknownAnswer() {
        UUID unknown = UUID.randomUUID();
        when(answers.findWithPartAndAttempt(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.answer(unknown))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("kappa latest delegates by scope; missing evaluations 404")
    void kappaLatestDelegates() {
        SmartMarkAgreementEvaluation evaluation = new SmartMarkAgreementEvaluation(
                SmartMarkAgreementEvaluation.SCOPE_ALL, null, 12, 0.75, 0.9, 0.6, MARKER);
        when(agreementEvaluations.findFirstByScopeOrderByComputedAtDesc(
                SmartMarkAgreementEvaluation.SCOPE_ALL)).thenReturn(Optional.of(evaluation));

        var view = controller.latestKappa(null);

        assertThat(view.kappa()).isEqualTo(0.75);
        assertThat(view.passed()).isTrue();
        assertThat(view.sampleSize()).isEqualTo(12);

        when(agreementEvaluations.findFirstByScopeOrderByComputedAtDesc(
                SmartMarkAgreementEvaluation.SCOPE_ALL)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.latestKappa(null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("human mark and kappa evaluate delegate to the marking service")
    void mutationsDelegate() {
        when(teacherMarkingService.recordHumanMark(any(), any(), anyInt(), any(), any()))
                .thenReturn(new HumanMark(answer, MARKER, 2, Map.of(), "ok"));
        controller.humanMark(MARKER, answer.id(),
                new TeacherMarkingController.HumanMarkRequest(2, Map.of("mp", 1), "ok"));
        verify(teacherMarkingService).recordHumanMark(answer.id(), MARKER, 2, Map.of("mp", 1), "ok");

        when(teacherMarkingService.evaluateAgreement(any(), any())).thenReturn(
                new SmartMarkAgreementEvaluation(
                        SmartMarkAgreementEvaluation.SCOPE_ALL, null, 2, 1.0, 1.0, 0.6, MARKER));
        controller.evaluateKappa(MARKER, null);
        verify(teacherMarkingService).evaluateAgreement(null, MARKER);
    }

    @Test
    @DisplayName("v1 queue pagination is opt-in: params produce an envelope with the database's own counts; the total order is createdAt asc then id asc")
    void queuePaginationOptIn() {
        Page<Answer> page = new PageImpl<>(List.of(answer), PageRequest.of(0, 50), 51);
        when(answers.findPageByMarkingState(eq(Answer.MarkingState.PENDING), any(Pageable.class)))
                .thenReturn(page);

        Object paged = controller.queue("PENDING", 0, 50);

        assertThat(paged).isInstanceOf(TeacherViews.AnswerMarkingPageView.class);
        var view = (TeacherViews.AnswerMarkingPageView) paged;
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().get(0).learnerDisplayName()).isEqualTo("Ada Learner");
        assertThat(view.page()).isZero();
        assertThat(view.size()).isEqualTo(50);
        assertThat(view.totalElements()).isEqualTo(51);
        assertThat(view.totalPages()).isEqualTo(2);

        // the total order that makes page boundaries stable is pinned here
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(answers).findPageByMarkingState(eq(Answer.MarkingState.PENDING), pageable.capture());
        Sort sort = pageable.getValue().getSort();
        assertThat(sort.getOrderFor("createdAt")).isNotNull();
        assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.ASC);

        // bounds fail loud (400 semantics), never silently clamp
        assertThatThrownBy(() -> controller.queue("PENDING", 0, 201))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> controller.queue("PENDING", -1, 10))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("kappa evaluate delegates the PAPER scope to the marking service")
    void kappaEvaluatePaperScope() {
        UUID paperId = UUID.randomUUID();
        when(teacherMarkingService.evaluateAgreement(paperId, MARKER)).thenReturn(
                new SmartMarkAgreementEvaluation(
                        SmartMarkAgreementEvaluation.SCOPE_PAPER, paperId, 5, 0.9, 0.95, 0.6,
                        MARKER));

        var view = controller.evaluateKappa(MARKER,
                new TeacherMarkingController.KappaScopeRequest(paperId));

        verify(teacherMarkingService).evaluateAgreement(paperId, MARKER);
        assertThat(view.scope()).isEqualTo("PAPER");
        assertThat(view.paperId()).isEqualTo(paperId);
    }

    @Test
    @DisplayName("kappa latest resolves the PAPER-scoped row by paperId")
    void kappaLatestPaperScope() {
        UUID paperId = UUID.randomUUID();
        SmartMarkAgreementEvaluation evaluation = new SmartMarkAgreementEvaluation(
                SmartMarkAgreementEvaluation.SCOPE_PAPER, paperId, 7, 0.8, 0.85, 0.6, MARKER);
        when(agreementEvaluations.findFirstByScopeAndExamPaperIdOrderByComputedAtDesc(
                SmartMarkAgreementEvaluation.SCOPE_PAPER, paperId))
                .thenReturn(Optional.of(evaluation));

        var view = controller.latestKappa(paperId);

        assertThat(view.scope()).isEqualTo("PAPER");
        assertThat(view.paperId()).isEqualTo(paperId);
        assertThat(view.sampleSize()).isEqualTo(7);
    }
}

package com.syllabai.smartmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.EvidencePublisher;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.shared.events.SmartMarkCompletedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Smart Mark authority semantics (Master Spec §15, F-161): before the κ gate
 * passes, smart marks are provisional (no evidence); after the gate passes,
 * an accepted run drives the evidence contract exactly once. The gate fails
 * closed when no evaluation exists.
 */
class SmartMarkServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID TOPIC = UUID.randomUUID();
    private static final UUID PAPER = UUID.randomUUID();

    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final SmartMarkResultRepository smartMarkResults = mock(SmartMarkResultRepository.class);
    private final SmartMarkAgreementEvaluationRepository agreementEvaluations =
            mock(SmartMarkAgreementEvaluationRepository.class);
    private final QuestionTopicRepository questionTopics = mock(QuestionTopicRepository.class);
    private final EvidencePublisher evidencePublisher = mock(EvidencePublisher.class);
    private final List<Object> published = new ArrayList<>();

    private final Question question;
    private final QuestionVersion version;
    private final QuestionPart part;
    private final Answer answer;

    private final SmartMarkService service = new SmartMarkService(
            answers, questionVersions, markSchemes, smartMarkResults, agreementEvaluations,
            questionTopics, evidencePublisher,
            new SmartMarkPipeline(
                    ctx -> new MarkingCandidate("test-model", ctx.points().stream()
                            .map(p -> new MarkingCandidate.Allocation(p.id(), p.ref(), false,
                                    "", "nothing earned"))
                            .toList(), 0.5, "raw"),
                    List.of(new BoundsMarkingValidator(), new CoverageMarkingValidator(),
                            new MarkSumMarkingValidator())),
            published::add);

    SmartMarkServiceTest() {
        question = new Question("q-1", Question.Type.STRUCTURED, "stem", 2, 3, 120,
                "Explain", TOPIC, Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        question.attachToPaper(PAPER);
        version = new QuestionVersion(question, 1, "stem", 2, 3, 120, "Explain",
                QuestionVersion.ValidationState.VALIDATED, "doc", 0.9, "test");
        TestIds.withId(version, UUID.randomUUID());
        part = new QuestionPart(version, "a", "part a", "State", 2, 0);
        TestIds.withId(part, UUID.randomUUID());
        version.addPart(part);

        Attempt attempt = new Attempt(LEARNER, question, null, false, null,
                5000L, 4, false, true, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        attempt.beginMarking();
        answer = new Answer(attempt, part, "an answer with water");
        TestIds.withId(answer, UUID.randomUUID());

        MarkScheme scheme = new MarkScheme(version, "1", "ms", "test");
        TestIds.withId(scheme, UUID.randomUUID());
        MarkPoint pointA = new MarkPoint(scheme, part, "1-a", 0, "iron oxide", 1, List.of(), 0.9);
        TestIds.withId(pointA, UUID.randomUUID());
        MarkPoint pointB = new MarkPoint(scheme, part, "1-a", 1, "water", 1, List.of(), 0.9);
        TestIds.withId(pointB, UUID.randomUUID());
        scheme.addPoint(pointA);
        scheme.addPoint(pointB);

        when(answers.findWithPartAndAttempt(answer.id())).thenReturn(Optional.of(answer));
        when(questionVersions.findByQuestionIdOrderByVersionDesc(question.id()))
                .thenReturn(List.of(version));
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()))
                .thenReturn(Optional.of(scheme));
        when(answers.findByAttemptIdOrderByQuestionPartId(any())).thenReturn(List.of());
        when(smartMarkResults.save(any(SmartMarkResult.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(questionTopics.findByQuestionId(question.id())).thenReturn(List.of());
    }

    @Test
    @DisplayName("before the κ gate passes, smart marks stay provisional — no evidence fires")
    void provisionalBeforeGate() {
        when(agreementEvaluations.findFirstByScopeOrderByComputedAtDesc(
                SmartMarkAgreementEvaluation.SCOPE_ALL)).thenReturn(Optional.empty());

        SmartMarkResult result = service.markAnswer(answer.id());

        assertThat(result.validationPassed()).isTrue();
        assertThat(result.marksAwarded()).isZero();   // the fake generator awards nothing
        assertThat(answer.markingState()).isEqualTo(Answer.MarkingState.SMART_MARKED);
        verify(evidencePublisher, never()).publishGraded(any(), any(), any());
        SmartMarkCompletedEvent event = (SmartMarkCompletedEvent) published.get(0);
        assertThat(event.authoritative()).isFalse();
    }

    @Test
    @DisplayName("after the κ gate passes, an accepted run drives evidence exactly once")
    void authoritativeAfterGate() {
        when(agreementEvaluations.findFirstByScopeOrderByComputedAtDesc(
                SmartMarkAgreementEvaluation.SCOPE_ALL))
                .thenReturn(Optional.of(new SmartMarkAgreementEvaluation(
                        SmartMarkAgreementEvaluation.SCOPE_ALL, null, 10, 0.72, 0.9, 0.6, null)));
        when(evidencePublisher.publishGraded(any(), any(), any())).thenReturn(true);

        SmartMarkResult result = service.markAnswer(answer.id());

        assertThat(result.validationPassed()).isTrue();
        verify(evidencePublisher).publishGraded(any(), any(), any());
        SmartMarkCompletedEvent event = (SmartMarkCompletedEvent) published.get(0);
        assertThat(event.authoritative()).isTrue();
    }

    @Test
    @DisplayName("kappa gate fails closed: no evaluation rows = gated")
    void gateFailsClosed() {
        when(agreementEvaluations.findFirstByScopeOrderByComputedAtDesc(any())).thenReturn(Optional.empty());
        when(agreementEvaluations.findFirstByScopeAndExamPaperIdOrderByComputedAtDesc(
                any(), any())).thenReturn(Optional.empty());
        assertThat(service.kappaGatePassed(PAPER)).isFalse();
    }
}

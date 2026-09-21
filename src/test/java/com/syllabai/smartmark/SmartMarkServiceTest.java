package com.syllabai.smartmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.AttemptRepository;
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
    private final AttemptRepository attempts = mock(AttemptRepository.class);
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
    private final Attempt attempt;
    private final Answer answer;
    private final MarkScheme scheme;

    private final SmartMarkService service = new SmartMarkService(
            answers, attempts, questionVersions, markSchemes, smartMarkResults, agreementEvaluations,
            questionTopics, evidencePublisher,
            new SmartMarkPipeline(
                    ctx -> new MarkingCandidate("test-model", ctx.points().stream()
                            .map(p -> new MarkingCandidate.Allocation(p.id(), p.ref(), 0,
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

        attempt = new Attempt(LEARNER, question, null, false, null,
                5000L, 4, false, true, "test");
        TestIds.withId(attempt, UUID.randomUUID());
        attempt.beginMarking();
        answer = new Answer(attempt, part, "an answer with water");
        TestIds.withId(answer, UUID.randomUUID());

        scheme = new MarkScheme(version, "1", "ms", "test");
        TestIds.withId(scheme, UUID.randomUUID());
        MarkPoint pointA = new MarkPoint(scheme, part, "1-a", 0, "iron oxide", 1, List.of(), 0.9);
        TestIds.withId(pointA, UUID.randomUUID());
        MarkPoint pointB = new MarkPoint(scheme, part, "1-a", 1, "water", 1, List.of(), 0.9);
        TestIds.withId(pointB, UUID.randomUUID());
        scheme.addPoint(pointA);
        scheme.addPoint(pointB);
        scheme.validate();   // matches the validated-only finder the service now uses

        when(answers.findAttemptIdById(any(UUID.class))).thenReturn(Optional.of(attempt.id()));
        when(attempts.findByIdForUpdate(any(UUID.class))).thenReturn(Optional.of(attempt));
        when(answers.findWithPartAndAttempt(answer.id())).thenReturn(Optional.of(answer));
        when(questionVersions.findByQuestionIdOrderByVersionDesc(question.id()))
                .thenReturn(List.of(version));
        when(markSchemes.findFirstByQuestionVersionIdAndValidationStateOrderByCreatedAtDesc(
                version.id(), MarkScheme.ValidationState.VALIDATED))
                .thenReturn(Optional.of(scheme));
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()))
                .thenReturn(Optional.of(scheme));
        when(answers.findByAttemptIdOrderByQuestionPartId(attempt.id()))
                .thenReturn(List.of(answer));
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
    @DisplayName("accepted runs stamp the scheme provenance they were marked against")
    void stampsSchemeProvenance() {
        when(agreementEvaluations.findFirstByScopeOrderByComputedAtDesc(
                SmartMarkAgreementEvaluation.SCOPE_ALL)).thenReturn(Optional.empty());

        SmartMarkResult result = service.markAnswer(answer.id());

        assertThat(result.markSchemeId()).isEqualTo(scheme.id());
        assertThat(result.schemeValidationState())
                .isEqualTo(MarkScheme.ValidationState.VALIDATED.name());
    }

    @Test
    @DisplayName("a SUGGESTED scheme never backs marking — honest refusal row, state untouched")
    void suggestedSchemeRefused() {
        MarkScheme suggested = new MarkScheme(version, "2", "ms-2", "test");
        TestIds.withId(suggested, UUID.randomUUID());
        suggested.addPoint(new MarkPoint(suggested, part, "2-a", 0, "iron oxide", 1, List.of(), 0.9));
        suggested.addPoint(new MarkPoint(suggested, part, "2-a", 1, "water", 1, List.of(), 0.9));
        when(markSchemes.findFirstByQuestionVersionIdAndValidationStateOrderByCreatedAtDesc(
                version.id(), MarkScheme.ValidationState.VALIDATED))
                .thenReturn(Optional.empty());
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()))
                .thenReturn(Optional.of(suggested));

        SmartMarkResult result = service.markAnswer(answer.id());

        assertThat(result.validationPassed()).isFalse();
        assertThat(result.failureReason()).isEqualTo("SCHEME_NOT_VALIDATED");
        assertThat(result.marksAwarded()).isZero();
        assertThat(result.markSchemeId()).isEqualTo(suggested.id());
        assertThat(result.schemeValidationState())
                .isEqualTo(MarkScheme.ValidationState.SUGGESTED.name());
        assertThat(answer.markingState()).isEqualTo(Answer.MarkingState.PENDING);
        verify(evidencePublisher, never()).publishGraded(any(), any(), any());
        SmartMarkCompletedEvent event = (SmartMarkCompletedEvent) published.get(0);
        assertThat(event.validationPassed()).isFalse();
        assertThat(event.failureReason()).isEqualTo("SCHEME_NOT_VALIDATED");
        assertThat(event.authoritative()).isFalse();
        assertThat(event.marksPossible()).isEqualTo(2);   // refused scheme's in-scope ceiling
    }

    @Test
    @DisplayName("a FLAGGED scheme never backs marking (V20 rule enforced on the marking path)")
    void flaggedSchemeRefused() {
        scheme.flag();   // setup already validated it — flag is the V20 poisoned state
        when(markSchemes.findFirstByQuestionVersionIdAndValidationStateOrderByCreatedAtDesc(
                version.id(), MarkScheme.ValidationState.VALIDATED))
                .thenReturn(Optional.empty());

        SmartMarkResult result = service.markAnswer(answer.id());

        assertThat(result.validationPassed()).isFalse();
        assertThat(result.failureReason()).isEqualTo("SCHEME_NOT_VALIDATED");
        assertThat(result.schemeValidationState())
                .isEqualTo(MarkScheme.ValidationState.FLAGGED.name());
        assertThat(answer.markingState()).isEqualTo(Answer.MarkingState.PENDING);
    }

    @Test
    @DisplayName("kappa gate fails closed: no evaluation rows = gated")
    void gateFailsClosed() {
        when(agreementEvaluations.findFirstByScopeOrderByComputedAtDesc(any())).thenReturn(Optional.empty());
        when(agreementEvaluations.findFirstByScopeAndExamPaperIdOrderByComputedAtDesc(
                any(), any())).thenReturn(Optional.empty());
        assertThat(service.kappaGatePassed(PAPER)).isFalse();
    }

    @Test
    @DisplayName("multi-part κ-released smart mark: evidence waits for the completing part, then fires once")
    void multiPartKappaReleasedSmartMarkWaitsForCompletion() {
        // a second part on the same attempt — while it is PENDING the attempt's
        // total is partial, so part a's authoritative smart mark must NOT fire yet
        // (the production regression fe01b87 pins for the human path holds here too)
        QuestionPart partB = new QuestionPart(version, "b", "part b", "State", 2, 1);
        TestIds.withId(partB, UUID.randomUUID());
        version.addPart(partB);
        Answer answerB = new Answer(attempt, partB, "a second written answer");
        TestIds.withId(answerB, UUID.randomUUID());
        when(answers.findWithPartAndAttempt(answerB.id())).thenReturn(Optional.of(answerB));
        when(answers.findByAttemptIdOrderByQuestionPartId(attempt.id()))
                .thenReturn(List.of(answer, answerB));

        // a scheme covering BOTH parts (in-scope points drive the pipeline)
        MarkScheme bothParts = new MarkScheme(version, "2", "ms-b", "test");
        TestIds.withId(bothParts, UUID.randomUUID());
        MarkPoint pointA2 = new MarkPoint(bothParts, part, "1-a", 0, "first answer", 1, List.of(), 0.9);
        TestIds.withId(pointA2, UUID.randomUUID());
        MarkPoint pointA3 = new MarkPoint(bothParts, part, "1-a", 1, "first reason", 1, List.of(), 0.9);
        TestIds.withId(pointA3, UUID.randomUUID());
        MarkPoint pointB1 = new MarkPoint(bothParts, partB, "1-b", 0, "second answer", 1, List.of(), 0.9);
        TestIds.withId(pointB1, UUID.randomUUID());
        MarkPoint pointB2 = new MarkPoint(bothParts, partB, "1-b", 1, "second reason", 1, List.of(), 0.9);
        TestIds.withId(pointB2, UUID.randomUUID());
        bothParts.addPoint(pointA2);
        bothParts.addPoint(pointA3);
        bothParts.addPoint(pointB1);
        bothParts.addPoint(pointB2);
        bothParts.validate();   // G-2: only a VALIDATED scheme backs marking
        when(markSchemes.findFirstByQuestionVersionIdAndValidationStateOrderByCreatedAtDesc(
                version.id(), MarkScheme.ValidationState.VALIDATED))
                .thenReturn(Optional.of(bothParts));

        // κ gate released
        when(agreementEvaluations.findFirstByScopeOrderByComputedAtDesc(
                SmartMarkAgreementEvaluation.SCOPE_ALL))
                .thenReturn(Optional.of(new SmartMarkAgreementEvaluation(
                        SmartMarkAgreementEvaluation.SCOPE_ALL, null, 10, 0.72, 0.9, 0.6, null)));

        // a pipeline that awards every in-scope point (accepted, full marks)
        SmartMarkService fullMarks = new SmartMarkService(
                answers, attempts, questionVersions, markSchemes, smartMarkResults, agreementEvaluations,
                questionTopics, evidencePublisher,
                new SmartMarkPipeline(
                        ctx -> new MarkingCandidate("test-model", ctx.points().stream()
                                .map(p -> new MarkingCandidate.Allocation(p.id(), p.ref(), p.marks(),
                                        "quoted learner text", "earned"))
                                .toList(), 0.9, "raw"),
                        List.of(new BoundsMarkingValidator(), new CoverageMarkingValidator(),
                                new MarkSumMarkingValidator())),
                published::add);

        // part a: authoritative (κ released) but the attempt is INCOMPLETE — no evidence
        SmartMarkResult resultA = fullMarks.markAnswer(answer.id());
        assertThat(resultA.validationPassed()).isTrue();
        verify(evidencePublisher, never()).publishGraded(any(), any(), any());
        assertThat(attempt.marksAwarded()).isEqualTo(2);   // the partial research view
        assertThat(((SmartMarkCompletedEvent) published.get(0)).authoritative()).isTrue();

        // the completing smart mark fires the evidence ONCE, with the FULL settled total
        SmartMarkResult resultB = fullMarks.markAnswer(answerB.id());
        assertThat(resultB.validationPassed()).isTrue();
        verify(evidencePublisher, times(1)).publishGraded(any(), any(), any());
        assertThat(attempt.marksAwarded()).isEqualTo(4);
        // two completed-events, exactly one evidence call — no duplicate evidence
        assertThat(published).hasSize(2);
    }
}

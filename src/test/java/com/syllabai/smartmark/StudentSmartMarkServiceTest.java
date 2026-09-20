package com.syllabai.smartmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.infrastructure.llm.LlmProvider;
import com.syllabai.infrastructure.llm.LlmRequest;
import com.syllabai.infrastructure.llm.LlmResponse;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.shared.events.SmartFeedbackExplainedEvent;
import com.syllabai.shared.events.SmartImprovementPlanViewedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The student-facing Smart Mark surface (F-047 learner half): one engine (the
 * teacher-lane pipeline), reveal-policy-consistent scheme gating, pre-settlement
 * only, κ-gate honesty, and deterministic guards on the feedback actions.
 */
class StudentSmartMarkServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();

    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final SmartMarkService smartMarkService = mock(SmartMarkService.class);
    private final SmartMarkResultRepository smartMarkResults = mock(SmartMarkResultRepository.class);
    private final LlmProvider llm = mock(LlmProvider.class);
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher events = published::add;

    private final Question question;
    private final QuestionVersion version;
    private final QuestionPart partA;
    private final Attempt attempt;
    private final Answer answerA;
    private final MarkScheme scheme;
    private final MarkPoint pointA1;
    private final MarkPoint pointA2;

    private StudentSmartMarkService service(String revealPolicy) {
        return new StudentSmartMarkService(attempts, answers, questionVersions, markSchemes,
                smartMarkService, smartMarkResults, llm, events, revealPolicy);
    }

    StudentSmartMarkServiceTest() {
        question = new Question("sme-eq-1-1-q3", Question.Type.STRUCTURED, "", 5, 3, 360,
                "Explain", UUID.randomUUID(), Question.Provenance.PAST_PAPER);
        TestIds.withId(question, UUID.randomUUID());
        version = new QuestionVersion(question, 1, "", 5, 3, 360, "Explain",
                QuestionVersion.ValidationState.VALIDATED, "sme-eq-igcse-chemistry-19",
                null, "sme-corpus-import-v1 (ADR-026)");
        TestIds.withId(version, UUID.randomUUID());
        partA = new QuestionPart(version, "a", "part a", "State", 2, 0);
        TestIds.withId(partA, UUID.randomUUID());
        version.addPart(partA);

        attempt = new Attempt(LEARNER, question, null, false, null,
                30_000L, 4, false, false, "web-structured-v1");
        TestIds.withId(attempt, UUID.randomUUID());
        attempt.beginMarking();
        answerA = new Answer(attempt, partA, "sodium chloride dissolves");
        TestIds.withId(answerA, UUID.randomUUID());

        scheme = new MarkScheme(version, "1", "doc-1", "llm-structured");
        pointA1 = new MarkPoint(scheme, partA, "a-i", 0, "state the salt", 1, List.of(), 1.0);
        TestIds.withId(pointA1, UUID.randomUUID());
        pointA2 = new MarkPoint(scheme, partA, "a-ii", 1, "explain the lattice", 1, List.of(), 1.0);
        TestIds.withId(pointA2, UUID.randomUUID());
        scheme.addPoint(pointA1);
        scheme.addPoint(pointA2);
        scheme.validate();

        when(attempts.findByIdForUpdate(attempt.id())).thenReturn(Optional.of(attempt));
        when(attempts.findById(attempt.id())).thenReturn(Optional.of(attempt));
        when(answers.findByAttemptIdOrderByQuestionPartId(attempt.id()))
                .thenReturn(List.of(answerA));
        when(questionVersions.findByQuestionIdOrderByVersionDesc(question.id()))
                .thenReturn(List.of(version));
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()))
                .thenReturn(Optional.of(scheme));
        when(smartMarkService.kappaGatePassed(any())).thenReturn(false);
        when(smartMarkService.markAnswer(answerA.id())).thenReturn(acceptedResult());
        when(llm.available()).thenReturn(true);
        when(llm.generate(any())).thenReturn(new LlmResponse(
                "Here is why.", "groq", "test-model", 10L, 10, 10));
    }

    private SmartMarkResult acceptedResult() {
        SmartMarkResult result = new SmartMarkResult(answerA, "test-model", 1, 0.9, true,
                List.of(
                        Map.ofEntries(
                                Map.entry("markPointId", pointA1.id().toString()),
                                Map.entry("ref", "a-i"),
                                Map.entry("awarded", true),
                                Map.entry("evidence", "sodium chloride"),
                                Map.entry("rationale", "named the salt")),
                        Map.ofEntries(
                                Map.entry("markPointId", pointA2.id().toString()),
                                Map.entry("ref", "a-ii"),
                                Map.entry("awarded", false),
                                Map.entry("evidence", ""),
                                Map.entry("rationale", "no lattice explanation"))),
                null, "{}");
        return TestIds.withId(result, UUID.randomUUID());
    }

    @Test
    @DisplayName("smart-mark runs the teacher-lane engine per part and projects point text")
    void smartMarkAttemptsRunsSharedEngine() {
        var view = service("VALIDATED_ONLY").smartMarkAttempt(LEARNER, attempt.id());

        verify(smartMarkService).markAnswer(answerA.id());
        assertThat(view.attemptId()).isEqualTo(attempt.id());
        assertThat(view.schemeValidationState()).isEqualTo("VALIDATED");
        assertThat(view.parts()).hasSize(1);
        var part = view.parts().get(0);
        assertThat(part.partId()).isEqualTo(partA.id());
        assertThat(part.marksAwarded()).isEqualTo(1);
        assertThat(part.authoritative()).isFalse(); // no κ evaluation — honest provisional
        assertThat(part.breakdown()).hasSize(2);
        assertThat(part.breakdown().get(0).pointText()).isEqualTo("state the salt");
        assertThat(part.breakdown().get(0).awarded()).isTrue();
        assertThat(part.breakdown().get(0).evidence()).isEqualTo("sodium chloride");
        assertThat(part.breakdown().get(1).awarded()).isFalse();
        // one engine: the student pass routed through the same markAnswer the
        // teacher queue calls (event publication lives inside the real service)
        verify(smartMarkService).markAnswer(answerA.id());
    }

    @Test
    @DisplayName("κ-released marks report authoritative=true — never a silent gate")
    void authoritativeFollowsKappaGate() {
        when(smartMarkService.kappaGatePassed(any())).thenReturn(true);

        var view = service("VALIDATED_ONLY").smartMarkAttempt(LEARNER, attempt.id());

        assertThat(view.parts().get(0).authoritative()).isTrue();
    }

    @Test
    @DisplayName("another learner's attempt id is a plain 404 — no existence leak")
    void foreignAttemptIsNotFound() {
        assertThatThrownBy(() -> service("VALIDATED_ONLY")
                .smartMarkAttempt(UUID.randomUUID(), attempt.id()))
                .isInstanceOf(NotFoundException.class);
        verify(smartMarkService, never()).markAnswer(any());
    }

    @Test
    @DisplayName("non-structured attempts are a 400")
    void mcqAttemptIsBadRequest() {
        Question mcq = new Question("sme-eq-1-1-q4", Question.Type.MCQ_SINGLE, "", 1, 3, 120,
                "Choose", UUID.randomUUID(), Question.Provenance.PAST_PAPER);
        TestIds.withId(mcq, UUID.randomUUID());
        Attempt mcqAttempt = new Attempt(LEARNER, mcq, null, true, 1,
                5_000L, 3, false, false, "web-mcq-v1");
        TestIds.withId(mcqAttempt, UUID.randomUUID());
        when(attempts.findByIdForUpdate(mcqAttempt.id())).thenReturn(Optional.of(mcqAttempt));

        assertThatThrownBy(() -> service("VALIDATED_ONLY")
                .smartMarkAttempt(LEARNER, mcqAttempt.id()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("a settled attempt (self-marked) refuses smart marking — single settlement")
    void settledAttemptConflicts() {
        answerA.selfMarked(2);

        assertThatThrownBy(() -> service("VALIDATED_ONLY")
                .smartMarkAttempt(LEARNER, attempt.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("settled");
        verify(smartMarkService, never()).markAnswer(any());
    }

    @Test
    @DisplayName("SUGGESTED scheme is withheld under VALIDATED_ONLY, serves under INCLUDE_SUGGESTED")
    void revealPolicyGatesSmartMarking() {
        MarkScheme unvalidated = new MarkScheme(version, "1", "doc-1", "llm-structured");
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()))
                .thenReturn(Optional.of(unvalidated));

        assertThatThrownBy(() -> service("VALIDATED_ONLY")
                .smartMarkAttempt(LEARNER, attempt.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("teacher validation");
        verify(smartMarkService, never()).markAnswer(any());

        var view = service("INCLUDE_SUGGESTED").smartMarkAttempt(LEARNER, attempt.id());
        assertThat(view.schemeValidationState()).isEqualTo("SUGGESTED");
    }

    @Test
    @DisplayName("explanation is grounded on the accepted result and emits telemetry")
    void explainFeedbackGeneratesAndEmits() {
        when(smartMarkResults.findLatest(answerA.id()))
                .thenReturn(Optional.of(acceptedResult()));

        var view = service("VALIDATED_ONLY").explainFeedback(LEARNER, attempt.id(), partA.id());

        assertThat(view.explanation()).isEqualTo("Here is why.");
        assertThat(view.modelId()).isEqualTo("test-model");
        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).generate(request.capture());
        assertThat(request.getValue().systemPrompt()).contains("Never change a mark decision");
        assertThat(request.getValue().userPrompt())
                .contains("sodium chloride dissolves")
                .contains("state the salt")
                .contains("named the salt");
        assertThat(published).anySatisfy(e -> {
            assertThat(e).isInstanceOf(SmartFeedbackExplainedEvent.class);
            assertThat(((SmartFeedbackExplainedEvent) e).smartMarkResultId()).isNotNull();
        });
    }

    @Test
    @DisplayName("improvement plan prompts the missing points, not the whole scheme")
    void improvementPlanPromptsMissingPoints() {
        when(smartMarkResults.findLatest(answerA.id()))
                .thenReturn(Optional.of(acceptedResult()));

        var view = service("VALIDATED_ONLY").improvementPlan(LEARNER, attempt.id(), partA.id());

        assertThat(view.plan()).isEqualTo("Here is why.");
        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llm).generate(request.capture());
        assertThat(request.getValue().systemPrompt()).contains("do NOT");
        assertThat(request.getValue().userPrompt()).contains("no lattice explanation");
        assertThat(published).anySatisfy(e ->
                assertThat(e).isInstanceOf(SmartImprovementPlanViewedEvent.class));
    }

    @Test
    @DisplayName("feedback actions require an accepted smart mark result first (409)")
    void feedbackWithoutSmartMarkConflicts() {
        when(smartMarkResults.findLatest(answerA.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service("VALIDATED_ONLY")
                .explainFeedback(LEARNER, attempt.id(), partA.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("run Smart mark first");
        verify(llm, never()).generate(any());
    }

    @Test
    @DisplayName("feedback actions on a part the attempt does not carry are a 404")
    void feedbackForForeignPartIsNotFound() {
        when(smartMarkResults.findLatest(answerA.id())).thenReturn(Optional.of(acceptedResult()));

        assertThatThrownBy(() -> service("VALIDATED_ONLY")
                .explainFeedback(LEARNER, attempt.id(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("unavailable LLM chain degrades to 503, never a partial explanation")
    void unavailableChainFailsLoud() {
        when(smartMarkResults.findLatest(answerA.id()))
                .thenReturn(Optional.of(acceptedResult()));
        when(llm.available()).thenReturn(false);

        assertThatThrownBy(() -> service("VALIDATED_ONLY")
                .explainFeedback(LEARNER, attempt.id(), partA.id()))
                .isInstanceOf(SmartFeedbackGenerationException.class);
    }

    @Test
    @DisplayName("blank generation output is rejected, not served")
    void blankOutputFailsLoud() {
        when(smartMarkResults.findLatest(answerA.id()))
                .thenReturn(Optional.of(acceptedResult()));
        when(llm.generate(any(LlmRequest.class))).thenReturn(new LlmResponse(
                "  ", "groq", "test-model", 10L, 10, 10));

        assertThatThrownBy(() -> service("VALIDATED_ONLY")
                .improvementPlan(LEARNER, attempt.id(), partA.id()))
                .isInstanceOf(SmartFeedbackGenerationException.class);
    }
}

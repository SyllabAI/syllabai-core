package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.assessment.dto.SubmitAnswerRequest;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Evidence assembly (audit fix #1): the event must carry both the <em>expressed</em>
 * misconception (chosen distractor) and the <em>observed</em> set (every misconception
 * the item monitors) — otherwise a correct answer can never exercise BDT
 * update-on-correct downstream.
 */
class AssessmentServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID QUESTION_ID = UUID.randomUUID();
    private static final UUID TOPIC_NODE = UUID.randomUUID();
    private static final UUID OPTION_CORRECT = UUID.randomUUID();
    private static final UUID OPTION_TAGGED = UUID.randomUUID();
    private static final UUID MISCONCEPTION_1 = UUID.randomUUID();
    private static final UUID MISCONCEPTION_2 = UUID.randomUUID();

    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionTopicRepository questionTopics = mock(QuestionTopicRepository.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final AssessmentService service = new AssessmentService(
            questions, questionTopics, questionVersions, answers, attempts,
            new EvidencePublisher(published::add));

    private Question question() {
        // build child mocks first — Mockito forbids stubbing inside another when(...)
        QuestionOption correct = option(OPTION_CORRECT, true, null);
        QuestionOption tagged = option(OPTION_TAGGED, false, MISCONCEPTION_1);
        QuestionOption other = option(UUID.randomUUID(), false, MISCONCEPTION_2);
        Question question = Mockito.mock(Question.class);
        when(question.id()).thenReturn(QUESTION_ID);
        when(question.active()).thenReturn(true);
        when(question.marks()).thenReturn(1);
        when(question.primaryTopicNodeId()).thenReturn(TOPIC_NODE);
        when(question.options()).thenReturn(List.of(correct, tagged, other));
        return question;
    }

    private Question structuredQuestion() {
        Question question = Mockito.mock(Question.class);
        when(question.id()).thenReturn(QUESTION_ID);
        when(question.active()).thenReturn(true);
        when(question.type()).thenReturn(Question.Type.STRUCTURED);
        when(question.marks()).thenReturn(4);
        when(question.primaryTopicNodeId()).thenReturn(TOPIC_NODE);
        when(question.options()).thenReturn(List.of());
        return question;
    }

    private QuestionOption option(UUID id, boolean correct, UUID misconception) {
        QuestionOption o = Mockito.mock(QuestionOption.class);
        when(o.id()).thenReturn(id);
        when(o.correct()).thenReturn(correct);
        when(o.misconceptionNodeId()).thenReturn(misconception);
        when(o.label()).thenReturn("A");
        return o;
    }

    private SubmitAnswerRequest submit(UUID chosenOptionId) {
        return new SubmitAnswerRequest(QUESTION_ID, chosenOptionId, 1000L, 3, false, false);
    }

    @Test
    @DisplayName("a correct answer carries the monitored set as observed, expressed stays empty")
    void correctAnswerCarriesObservedMisconceptions() {
        // build the mock graph first — stubbing must never nest inside another when(...)
        Question question = question();
        when(questions.findWithOptions(QUESTION_ID)).thenReturn(Optional.of(question));
        when(questionTopics.findByQuestionId(QUESTION_ID)).thenReturn(List.of());
        when(attempts.save(org.mockito.ArgumentMatchers.any(Attempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.submit(LEARNER, submit(OPTION_CORRECT));

        AssessmentEvidenceRecordedEvent event = soleEvidenceEvent();
        assertThat(event.correctness()).isTrue();
        assertThat(event.misconceptionIds()).isEmpty();
        assertThat(event.observedMisconceptionIds())
                .containsExactlyInAnyOrder(MISCONCEPTION_1, MISCONCEPTION_2);
    }

    @Test
    @DisplayName("a tagged-distractor choice carries expressed + observed lists")
    void taggedDistractorCarriesExpressedAndObserved() {
        Question question = question();
        when(questions.findWithOptions(QUESTION_ID)).thenReturn(Optional.of(question));
        when(questionTopics.findByQuestionId(QUESTION_ID)).thenReturn(List.of());
        when(attempts.save(org.mockito.ArgumentMatchers.any(Attempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.submit(LEARNER, submit(OPTION_TAGGED));

        AssessmentEvidenceRecordedEvent event = soleEvidenceEvent();
        assertThat(event.correctness()).isFalse();
        assertThat(event.misconceptionIds()).containsExactly(MISCONCEPTION_1);
        assertThat(event.observedMisconceptionIds())
                .containsExactlyInAnyOrder(MISCONCEPTION_1, MISCONCEPTION_2);
    }

    private AssessmentEvidenceRecordedEvent soleEvidenceEvent() {
        // the only events AssessmentService publishes are evidence records
        assertThat(published).isNotEmpty();
        return published.stream()
                .filter(e -> e instanceof AssessmentEvidenceRecordedEvent)
                .map(e -> (AssessmentEvidenceRecordedEvent) e)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("structured submit stores PENDING answers and defers evidence to marking")
    void structuredSubmitDefersEvidence() {
        Question question = structuredQuestion();
        when(questions.findById(QUESTION_ID)).thenReturn(Optional.of(question));
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 4, 3, 240,
                "Explain", QuestionVersion.ValidationState.VALIDATED, "doc-1", 0.9, "test");
        QuestionPart partA = new QuestionPart(version, "a", "part a prompt", "State", 2, 0);
        QuestionPart partB = new QuestionPart(version, "b", "part b prompt", "Explain", 2, 1);
        version.addPart(partA);
        version.addPart(partB);
        when(questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID))
                .thenReturn(List.of(version));
        when(questionTopics.findByQuestionId(QUESTION_ID)).thenReturn(List.of());
        when(attempts.save(org.mockito.ArgumentMatchers.any(Attempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(answers.save(org.mockito.ArgumentMatchers.any(Answer.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new com.syllabai.assessment.dto.StructuredSubmitRequest(
                QUESTION_ID,
                List.of(new com.syllabai.assessment.dto.PartAnswerRequest(
                                TestIds.withId(version.parts().get(0), UUID.randomUUID()).id(),
                                "sodium chloride"),
                        new com.syllabai.assessment.dto.PartAnswerRequest(
                                TestIds.withId(version.parts().get(1), UUID.randomUUID()).id(),
                                "because ions")),
                5000L, 4, false, true);

        var view = service.submitStructured(LEARNER, request);

        assertThat(view.markingState()).isEqualTo("PENDING");
        assertThat(view.marksPossible()).isEqualTo(4);
        assertThat(view.parts()).hasSize(2);
        assertThat(view.parts()).allSatisfy(p -> {
            assertThat(p.markingState()).isEqualTo("PENDING");
            assertThat(p.marksAwarded()).isNull();
        });
        assertThat(published).isEmpty();   // evidence deferred to first authoritative mark
    }

    @Test
    @DisplayName("structured submit rejects a missing part answer")
    void structuredSubmitRejectsMissingPart() {
        Question question = structuredQuestion();
        when(questions.findById(QUESTION_ID)).thenReturn(Optional.of(question));
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 4, 3, 240,
                "Explain", QuestionVersion.ValidationState.VALIDATED, "doc-1", 0.9, "test");
        QuestionPart partA = TestIds.withId(
                new QuestionPart(version, "a", "part a prompt", "State", 2, 0), UUID.randomUUID());
        QuestionPart partB = TestIds.withId(
                new QuestionPart(version, "b", "part b prompt", "Explain", 2, 1), UUID.randomUUID());
        version.addPart(partA);
        version.addPart(partB);
        when(questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID))
                .thenReturn(List.of(version));
        when(attempts.save(org.mockito.ArgumentMatchers.any(Attempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(answers.save(org.mockito.ArgumentMatchers.any(Answer.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new com.syllabai.assessment.dto.StructuredSubmitRequest(
                QUESTION_ID,
                List.of(new com.syllabai.assessment.dto.PartAnswerRequest(
                        partA.id(), "partial")),
                5000L, 4, false, false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.submitStructured(LEARNER, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing answer for part");
    }

    @Test
    @DisplayName("structured submit is refused for a SUGGESTED (unvalidated) version — fail-closed")
    void structuredSubmitRefusesUnvalidated() {
        Question question = structuredQuestion();
        when(questions.findById(QUESTION_ID)).thenReturn(Optional.of(question));
        QuestionVersion version = new QuestionVersion(question, 1, "stem", 4, 3, 240,
                "Explain", QuestionVersion.ValidationState.SUGGESTED, "doc-1", 0.9, "test");
        QuestionPart partA = TestIds.withId(
                new QuestionPart(version, "a", "part a prompt", "State", 2, 0), UUID.randomUUID());
        version.addPart(partA);
        when(questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID))
                .thenReturn(List.of(version));

        var request = new com.syllabai.assessment.dto.StructuredSubmitRequest(
                QUESTION_ID,
                List.of(new com.syllabai.assessment.dto.PartAnswerRequest(
                        partA.id(), "attempt against unvalidated content")),
                5000L, 4, false, false);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.submitStructured(LEARNER, request))
                .isInstanceOf(com.syllabai.shared.NotFoundException.class);
        verify(attempts, never()).save(org.mockito.ArgumentMatchers.any(Attempt.class));
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("MCQ submit path also refuses a STRUCTURED question without a VALIDATED version")
    void mcqSubmitRefusesUnvalidatedStructured() {
        Question question = structuredQuestion();
        when(questions.findWithOptions(QUESTION_ID)).thenReturn(Optional.of(question));
        when(questionVersions.findByQuestionIdOrderByVersionDesc(QUESTION_ID))
                .thenReturn(List.of());   // no version at all — fail closed

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.submit(LEARNER, submit(OPTION_CORRECT)))
                .isInstanceOf(com.syllabai.shared.NotFoundException.class);
        verify(attempts, never()).save(org.mockito.ArgumentMatchers.any(Attempt.class));
        assertThat(published).isEmpty();
    }
}

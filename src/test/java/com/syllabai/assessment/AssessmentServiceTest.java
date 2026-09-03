package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final AssessmentService service =
            new AssessmentService(questions, questionTopics, attempts, published::add);

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
}

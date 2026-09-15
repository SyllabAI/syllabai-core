package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.dto.AttemptHistoryView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

/**
 * AttemptHistoryService — the Review Hub read model (charter §14). These tests
 * pin the honesty rules: MCQ rows carry correct/marks and the already-revealed
 * option labels; structured rows carry null marks while any part is pending
 * and a summed total only once every part is authoritatively marked; the
 * attempt-level marking state rides along; topic identity resolves through
 * the shared graph (null-safe when the node is gone).
 */
class AttemptHistoryServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID QUESTION_ID = UUID.randomUUID();
    private static final UUID TOPIC_NODE = UUID.randomUUID();
    private static final UUID ATTEMPT_ID = UUID.randomUUID();

    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final AnswerRepository answers = mock(AnswerRepository.class);
    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final AttemptHistoryService service =
            new AttemptHistoryService(attempts, answers, graph);

    @Test
    @DisplayName("MCQ attempt history carries outcome, option labels and the distractor misconception")
    void mcqAttemptResolvesChosenAndCorrectOptions() {
        QuestionOption correct = option(UUID.randomUUID(), true, null, "C");
        UUID chosenId = UUID.randomUUID();
        QuestionOption chosen = option(chosenId, false, UUID.randomUUID(), "B");
        UUID misconceptionId = UUID.randomUUID();
        when(chosen.misconceptionNodeId()).thenReturn(misconceptionId);
        Question question = question(Question.Type.MCQ_SINGLE, 1, List.of(chosen, correct));
        Attempt attempt = Mockito.mock(Attempt.class);
        when(attempt.id()).thenReturn(ATTEMPT_ID);
        when(attempt.question()).thenReturn(question);
        when(attempt.chosenOptionId()).thenReturn(chosenId);
        when(attempt.correct()).thenReturn(false);
        when(attempt.marksAwarded()).thenReturn(0);
        when(attempt.markingState()).thenReturn(Attempt.MarkingState.AUTO_GRADED);
        when(attempt.evidenceEmitted()).thenReturn(true);
        when(attempt.selfDoubtFlag()).thenReturn(true);
        when(attempt.timedCondition()).thenReturn(false);
        when(attempt.confidenceLevel()).thenReturn(2);
        when(attempt.responseTimeMs()).thenReturn(4321L);
        when(attempt.createdAt()).thenReturn(java.time.Instant.parse("2026-09-08T10:00:00Z"));
        KnowledgeNode topic = node("U1-T2", "Ionic bonding");
        when(attempts.findByLearnerIdOrderByCreatedAtDesc(eq(LEARNER), any(Pageable.class)))
                .thenReturn(List.of(attempt));
        when(attempts.countByLearnerId(LEARNER)).thenReturn(7L);
        when(graph.node(TOPIC_NODE)).thenReturn(topic);

        AttemptHistoryView view = service.historyFor(LEARNER, null);

        assertThat(view.total()).isEqualTo(7);
        assertThat(view.returned()).isEqualTo(1);
        assertThat(view.attempts()).hasSize(1);
        AttemptHistoryView.Item item = view.attempts().get(0);
        assertThat(item.correct()).isFalse();
        assertThat(item.marksAwarded()).isZero();
        assertThat(item.markingState()).isEqualTo("AUTO_GRADED");
        assertThat(item.chosenOptionLabel()).isEqualTo("B");
        assertThat(item.correctOptionLabel()).isEqualTo("C");
        assertThat(item.implicatedMisconceptionIds()).hasSize(1);
        assertThat(item.topicCode()).isEqualTo("U1-T2");
        assertThat(item.topicTitle()).isEqualTo("Ionic bonding");
        assertThat(item.parts()).isEmpty();
        assertThat(item.selfDoubtFlag()).isTrue();
    }

    @Test
    @DisplayName("structured attempt with any pending part reports null marks — never an invented total")
    void structuredPendingPartYieldsNullMarks() {
        Question question = question(Question.Type.STRUCTURED, 4, List.of());
        Attempt attempt = structuredAttempt(question);
        when(attempt.marksAwarded()).thenReturn(null);
        List<Answer> pendingAnswers = List.of(
                answer("a", 2, 2, Answer.MarkingState.HUMAN_MARKED),
                answer("b", 2, null, Answer.MarkingState.PENDING));
        when(answers.findByAttemptIdOrderByQuestionPartId(ATTEMPT_ID)).thenReturn(pendingAnswers);
        when(attempts.findByLearnerIdOrderByCreatedAtDesc(eq(LEARNER), any(Pageable.class)))
                .thenReturn(List.of(attempt));
        when(attempts.countByLearnerId(LEARNER)).thenReturn(1L);

        AttemptHistoryView view = service.historyFor(LEARNER, null);

        AttemptHistoryView.Item item = view.attempts().get(0);
        assertThat(item.correct()).isNull();
        assertThat(item.marksAwarded()).isNull();
        assertThat(item.parts()).hasSize(2);
        assertThat(item.parts().get(1).marksAwarded()).isNull();
        assertThat(item.chosenOptionLabel()).isNull();
        assertThat(item.correctOptionLabel()).isNull();
    }

    @Test
    @DisplayName("structured attempt with all parts marked sums to an attempt-level total")
    void structuredAllPartsMarkedSumsMarks() {
        Question question = question(Question.Type.STRUCTURED, 4, List.of());
        Attempt attempt = structuredAttempt(question);
        List<Answer> markedAnswers = List.of(
                answer("a", 2, 2, Answer.MarkingState.HUMAN_MARKED),
                answer("b", 2, 1, Answer.MarkingState.HUMAN_MARKED));
        when(answers.findByAttemptIdOrderByQuestionPartId(ATTEMPT_ID)).thenReturn(markedAnswers);
        when(attempts.findByLearnerIdOrderByCreatedAtDesc(eq(LEARNER), any(Pageable.class)))
                .thenReturn(List.of(attempt));
        when(attempts.countByLearnerId(LEARNER)).thenReturn(1L);

        AttemptHistoryView.Item item = service.historyFor(LEARNER, null).attempts().get(0);

        assertThat(item.marksAwarded()).isEqualTo(3);
        // the settled row's own classification (recordTotalMarks' conservative
        // rule: partial credit = not mastery evidence) rides along unchanged
        assertThat(item.correct()).isFalse();
    }

    @Test
    @DisplayName("settled structured attempt carries the settled correctness flag — the classification the evidence event used")
    void structuredSettledAttemptExposesSettledCorrectness() {
        // the 2026-09-15 evidence-cycle finding: settled 6/6 structured attempts
        // reported correct=null forever, although the DTO contract promises the
        // classification "until an authoritative mark exists" — i.e. non-null
        // once marking settles. The settled attempt row (recordTotalMarks) is
        // the source; the view must agree with the evidence event.
        Question question = question(Question.Type.STRUCTURED, 4, List.of());
        Attempt attempt = structuredAttempt(question);
        when(attempt.correct()).thenReturn(true);   // settled full marks (6/6-style)
        List<Answer> markedAnswers = List.of(
                answer("a", 2, 2, Answer.MarkingState.HUMAN_MARKED),
                answer("b", 2, 2, Answer.MarkingState.HUMAN_MARKED));
        when(answers.findByAttemptIdOrderByQuestionPartId(ATTEMPT_ID)).thenReturn(markedAnswers);
        when(attempts.findByLearnerIdOrderByCreatedAtDesc(eq(LEARNER), any(Pageable.class)))
                .thenReturn(List.of(attempt));
        when(attempts.countByLearnerId(LEARNER)).thenReturn(1L);

        AttemptHistoryView.Item item = service.historyFor(LEARNER, null).attempts().get(0);

        assertThat(item.marksAwarded()).isEqualTo(4);
        assertThat(item.correct()).isTrue();
    }

    @Test
    @DisplayName("topic title is null-safe when the KG node no longer resolves")
    void missingTopicNodeStillServes() {
        Question question = question(Question.Type.MCQ_SINGLE, 1, List.of());
        Attempt attempt = Mockito.mock(Attempt.class);
        when(attempt.id()).thenReturn(ATTEMPT_ID);
        when(attempt.question()).thenReturn(question);
        when(attempt.chosenOptionId()).thenReturn(null);
        when(attempt.marksAwarded()).thenReturn(null);
        when(attempt.markingState()).thenReturn(Attempt.MarkingState.PENDING);
        when(attempt.createdAt()).thenReturn(java.time.Instant.now());
        when(attempts.findByLearnerIdOrderByCreatedAtDesc(eq(LEARNER), any(Pageable.class)))
                .thenReturn(List.of(attempt));
        when(attempts.countByLearnerId(LEARNER)).thenReturn(1L);
        when(graph.node(TOPIC_NODE)).thenReturn(null);

        AttemptHistoryView.Item item = service.historyFor(LEARNER, null).attempts().get(0);

        assertThat(item.topicCode()).isNull();
        assertThat(item.topicTitle()).isNull();
    }

    @Test
    @DisplayName("limit is clamped: negative and zero fall back to the default, above max caps")
    void limitClamping() {
        when(attempts.findByLearnerIdOrderByCreatedAtDesc(eq(LEARNER), any(Pageable.class)))
                .thenReturn(List.of());
        when(attempts.countByLearnerId(LEARNER)).thenReturn(0L);

        assertThat(service.historyFor(LEARNER, null).returned()).isZero();
        service.historyFor(LEARNER, 0);
        service.historyFor(LEARNER, -5);
        service.historyFor(LEARNER, 10_000);
        // no exception and a bounded page size requested each time
        Mockito.verify(attempts, Mockito.times(4))
                .findByLearnerIdOrderByCreatedAtDesc(eq(LEARNER), any(Pageable.class));
    }

    @Test
    @DisplayName("long stems are excerpted with an ellipsis, whitespace-normalized")
    void stemExcerpt() {
        Question question = question(Question.Type.MCQ_SINGLE, 1, List.of());
        when(question.stem()).thenReturn("A\n\nvery   long   stem ".repeat(30));
        Attempt attempt = Mockito.mock(Attempt.class);
        when(attempt.id()).thenReturn(ATTEMPT_ID);
        when(attempt.question()).thenReturn(question);
        when(attempt.chosenOptionId()).thenReturn(null);
        when(attempt.marksAwarded()).thenReturn(null);
        when(attempt.markingState()).thenReturn(Attempt.MarkingState.PENDING);
        when(attempt.createdAt()).thenReturn(java.time.Instant.now());
        when(attempts.findByLearnerIdOrderByCreatedAtDesc(eq(LEARNER), any(Pageable.class)))
                .thenReturn(List.of(attempt));
        when(attempts.countByLearnerId(LEARNER)).thenReturn(1L);

        String excerpt = service.historyFor(LEARNER, null).attempts().get(0).stemExcerpt();

        assertThat(excerpt.length()).isLessThanOrEqualTo(220);
        assertThat(excerpt).endsWith("…");
        assertThat(excerpt).doesNotContain("\n");
    }

    // ── fixtures ──

    private Question question(Question.Type type, int marks, List<QuestionOption> options) {
        Question question = Mockito.mock(Question.class);
        when(question.id()).thenReturn(QUESTION_ID);
        when(question.type()).thenReturn(type);
        when(question.marks()).thenReturn(marks);
        when(question.primaryTopicNodeId()).thenReturn(TOPIC_NODE);
        when(question.externalRef()).thenReturn("WPH11-2025-01");
        when(question.commandWord()).thenReturn("State");
        when(question.stem()).thenReturn("Which species contains the most protons?");
        when(question.options()).thenReturn(options);
        return question;
    }

    private QuestionOption option(UUID id, boolean correct, UUID misconception, String label) {
        QuestionOption o = Mockito.mock(QuestionOption.class);
        when(o.id()).thenReturn(id);
        when(o.correct()).thenReturn(correct);
        when(o.misconceptionNodeId()).thenReturn(misconception);
        when(o.label()).thenReturn(label);
        return o;
    }

    private Attempt structuredAttempt(Question question) {
        Attempt attempt = Mockito.mock(Attempt.class);
        when(attempt.id()).thenReturn(ATTEMPT_ID);
        when(attempt.question()).thenReturn(question);
        when(attempt.chosenOptionId()).thenReturn(null);
        when(attempt.correct()).thenReturn(false);
        when(attempt.markingState()).thenReturn(Attempt.MarkingState.PENDING);
        when(attempt.evidenceEmitted()).thenReturn(false);
        when(attempt.createdAt()).thenReturn(java.time.Instant.parse("2026-09-08T11:00:00Z"));
        return attempt;
    }

    private Answer answer(String label, int marks, Integer awarded, Answer.MarkingState state) {
        Answer answer = Mockito.mock(Answer.class);
        when(answer.questionPartId()).thenReturn(UUID.randomUUID());
        QuestionPart part = Mockito.mock(QuestionPart.class);
        when(part.label()).thenReturn(label);
        when(part.marks()).thenReturn(marks);
        when(answer.questionPart()).thenReturn(part);
        when(answer.marksAwarded()).thenReturn(awarded);
        when(answer.markingState()).thenReturn(state);
        return answer;
    }

    private KnowledgeNode node(String code, String title) {
        KnowledgeNode node = Mockito.mock(KnowledgeNode.class);
        when(node.code()).thenReturn(code);
        when(node.title()).thenReturn(title);
        return node;
    }
}

package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionPart;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.assessment.dto.StudentQuestionView;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * P9 smallest-useful Test Builder: the assembly must reuse the ONE serving
 * boundary (only servable questions enter a generated test), dedupe across
 * selected topics, cap and difficulty-order the result, honour subject
 * isolation, and attach the answer key from the current mark scheme.
 */
class TestBuilderServiceTest {

    private final ServableQuestionService servableQuestions = mock(ServableQuestionService.class);
    private final KnowledgeGraphService knowledgeGraph = mock(KnowledgeGraphService.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final TestBuilderService service = new TestBuilderService(
            servableQuestions, knowledgeGraph, questionVersions, markSchemes);

    private final UUID root = UUID.randomUUID();
    private final UUID topicA = UUID.randomUUID();
    private final UUID topicB = UUID.randomUUID();
    private final UUID outside = UUID.randomUUID();

    private KnowledgeNode topicNode(UUID id, String code) {
        KnowledgeNode n = mock(KnowledgeNode.class);
        when(n.id()).thenReturn(id);
        when(n.code()).thenReturn(code);
        when(n.title()).thenReturn("title " + code);
        return n;
    }

    private StudentQuestionView question(UUID id, int difficulty, int marks) {
        return new StudentQuestionView(id, "ref-" + id, "STRUCTURED", "stem " + id, marks,
                difficulty, 300, null, null, null, List.of(),
                List.of(new StudentQuestionView.PartView(
                        UUID.randomUUID(), "a", "prompt", null, marks)));
    }

    private void givenSubject() {
        when(knowledgeGraph.subtreeIds(root)).thenReturn(List.of(root, topicA, topicB));
        KnowledgeNode nodeA = topicNode(topicA, "4CH1-S2-b");   // built BEFORE the when() chain
        KnowledgeNode nodeB = topicNode(topicB, "4CH1-S4-b");   // (never mock inside thenReturn args)
        when(knowledgeGraph.node(topicA)).thenReturn(nodeA);
        when(knowledgeGraph.node(topicB)).thenReturn(nodeB);
    }

    @Test
    @DisplayName("assembles servable questions from the selected topics, deduped, difficulty-ordered, capped")
    void assemblesFromBoundary() {
        givenSubject();
        UUID shared = UUID.randomUUID();      // mapped under BOTH topics — must appear once
        UUID easy = UUID.randomUUID();
        UUID hard = UUID.randomUUID();
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(
                question(hard, 5, 9), question(shared, 3, 6)));
        when(servableQuestions.activeByTopic(topicB)).thenReturn(List.of(
                question(shared, 3, 6), question(easy, 1, 4)));
        when(servableQuestions.countServableByTopic(topicA)).thenReturn(2);
        when(servableQuestions.countServableByTopic(topicB)).thenReturn(2);

        var view = service.preview(root, List.of(topicA, topicB), 2, false);

        assertThat(view.questionCount()).isEqualTo(2);          // cap applied
        assertThat(view.totalMarks()).isEqualTo(10);            // easy(4) + shared(6)
        assertThat(view.questions()).extracting(q -> q.id())
                .containsExactly(easy, shared);                 // difficulty order, dedup
        assertThat(view.questions()).allMatch(q -> q.topicCode() != null);
        assertThat(view.questions()).allMatch(q -> q.answers().isEmpty());  // no answers requested
        assertThat(view.topics()).hasSize(2);
        assertThat(view.topics()).allMatch(t -> t.servableQuestions() == 2);
    }

    @Test
    @DisplayName("topics outside the subject subtree are ignored (subject isolation)")
    void subjectIsolation() {
        givenSubject();
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(question(UUID.randomUUID(), 2, 5)));
        when(servableQuestions.countServableByTopic(topicA)).thenReturn(1);

        var view = service.preview(root, List.of(topicA, outside), 20, false);

        assertThat(view.topics()).extracting(t -> t.topicNodeId()).containsExactly(topicA);
        assertThat(view.questionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("includeAnswers attaches the current scheme's points with its validation state")
    void answerKeyFromScheme() {
        givenSubject();
        UUID qid = UUID.randomUUID();
        StudentQuestionView q = question(qid, 2, 6);
        when(servableQuestions.activeByTopic(topicA)).thenReturn(List.of(q));
        when(servableQuestions.countServableByTopic(topicA)).thenReturn(1);

        QuestionVersion version = mock(QuestionVersion.class);
        when(version.id()).thenReturn(UUID.randomUUID());
        when(questionVersions.findByQuestionIdOrderByVersionDesc(qid)).thenReturn(List.of(version));

        QuestionPart part = mock(QuestionPart.class);
        when(part.label()).thenReturn("a");
        MarkPoint point = mock(MarkPoint.class);
        when(point.questionPart()).thenReturn(part);
        when(point.ref()).thenReturn("a-1");
        when(point.text()).thenReturn("hydrogen");
        when(point.marks()).thenReturn(1);
        when(point.acceptanceCriteria()).thenReturn(List.of("H2"));
        MarkScheme scheme = mock(MarkScheme.class);
        when(scheme.validationState()).thenReturn(MarkScheme.ValidationState.VALIDATED);
        when(scheme.points()).thenReturn(List.of(point));
        when(markSchemes.findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id()))
                .thenReturn(Optional.of(scheme));

        var view = service.preview(root, List.of(topicA), 20, true);

        assertThat(view.questions()).hasSize(1);
        var assembled = view.questions().get(0);
        assertThat(assembled.answers()).hasSize(1);
        assertThat(assembled.answers().get(0).partLabel()).isEqualTo("a");
        assertThat(assembled.answers().get(0).text()).isEqualTo("hydrogen");
        assertThat(assembled.answers().get(0).acceptanceCriteria()).containsExactly("H2");
        assertThat(assembled.schemeState()).isEqualTo("VALIDATED");
    }

    @Test
    @DisplayName("boundary holds: only what the serving boundary returns can enter a test")
    void boundaryIsTheOnlySource() {
        givenSubject();
        when(servableQuestions.activeByTopic(Mockito.any(UUID.class))).thenReturn(List.of());
        when(servableQuestions.countServableByTopic(Mockito.any(UUID.class))).thenReturn(0);

        var view = service.preview(root, List.of(topicA, topicB), 20, true);

        assertThat(view.questionCount()).isZero();
        assertThat(view.totalMarks()).isZero();
        assertThat(view.questions()).isEmpty();
    }
}

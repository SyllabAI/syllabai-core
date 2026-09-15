package com.syllabai.cla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.QuestionRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.assessment.ServableQuestionService;
import com.syllabai.curriculum.CurriculumVersion;
import com.syllabai.curriculum.Subject;
import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CLA contract §1 binding tests: resolution is server-side and fail-closed —
 * an unresolvable reference is a 404, never a best-effort guess; the
 * validation gate hides non-VALIDATED topics indistinguishably (no
 * validation-state oracle); curriculum identity is resolved from the owning
 * subject, never client-supplied.
 */
class ClaContextResolverTest {

    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID TOPIC = UUID.randomUUID();
    private static final UUID LEARNER = UUID.randomUUID();

    private final KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
    private final KnowledgeNodeRepository nodes = mock(KnowledgeNodeRepository.class);
    private final SubjectRepository subjects = mock(SubjectRepository.class);
    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final ServableQuestionService servableQuestions = mock(ServableQuestionService.class);
    private final AttemptRepository attempts = mock(AttemptRepository.class);

    private final ClaContextResolver resolver = new ClaContextResolver(graph, nodes, subjects,
            questions, examPapers, questionVersions, servableQuestions, attempts);

    private CurriculumVersion version;

    @BeforeEach
    void setUp() {
        version = new CurriculumVersion("Edexcel", "IAL", "IALCHEM2018",
                "International Advanced Level Chemistry", CurriculumVersion.Status.ACTIVE);
        Subject subject = new Subject(version, "4CH1", "Chemistry");
        subject.linkKnowledgeNode(ROOT);
        when(subjects.findByKnowledgeNodeId(ROOT)).thenReturn(Optional.of(subject));
        when(graph.tree(ROOT)).thenReturn(tree());

        KnowledgeNode topicNode = org.mockito.Mockito.mock(KnowledgeNode.class);
        when(topicNode.validationStatus()).thenReturn(KnowledgeNode.ValidationStatus.VALIDATED);
        when(topicNode.code()).thenReturn("IALCHEM2018-U1-T3");
        when(topicNode.title()).thenReturn("Bonding and structure");
        when(nodes.findById(TOPIC)).thenReturn(Optional.of(topicNode));
    }

    private NodeView tree() {
        NodeView topic = node(TOPIC, "IALCHEM2018-U1-T3", "TOPIC", "Bonding and structure");
        NodeView unit = node(UNIT, "IALCHEM2018-U1", "UNIT", "Unit 1", List.of(topic));
        return node(ROOT, "IALCHEM2018", "SUBJECT", "IAL Chemistry", List.of(unit));
    }

    private static NodeView node(UUID id, String code, String type, String title) {
        return new NodeView(id, code, type, title, null, "VALIDATED", null, List.of());
    }

    private static NodeView node(UUID id, String code, String type, String title, List<NodeView> children) {
        return new NodeView(id, code, type, title, null, "VALIDATED", null, children);
    }

    @Test
    @DisplayName("resolves a VALIDATED topic inside the subject subtree with server-side curriculum identity")
    void resolvesValidatedTopic() {
        ResourceContext context = resolver.resolveKgTopic(ROOT, TOPIC, LEARNER);

        assertThat(context.kind()).isEqualTo(ResourceContext.Kind.KG_TOPIC);
        assertThat(context.reference()).isEqualTo(TOPIC);
        assertThat(context.rootId()).isEqualTo(ROOT);
        assertThat(context.subjectCode()).isEqualTo("4CH1");
        assertThat(context.topicCode()).isEqualTo("IALCHEM2018-U1-T3");
        // curriculum identity resolved from the owning subject, never client-supplied
        assertThat(context.curriculumVersion().code()).isEqualTo("IALCHEM2018");
        assertThat(context.curriculumVersion().board()).isEqualTo("Edexcel");
        assertThat(context.curriculumVersion().status()).isEqualTo("ACTIVE");
        assertThat(context.validationState()).isEqualTo("VALIDATED");
        assertThat(context.learnerId()).isEqualTo(LEARNER);
        assertThat(context.resolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("fail-closed: a topic outside the subject subtree is a 404, not a cross-subject hop")
    void foreignTopicFailsClosed() {
        // an id that exists nowhere in the ROOT subtree — even a VALIDATED node
        // from another subject's registry — must fail closed (hard subject isolation)
        UUID outside = UUID.randomUUID();
        assertThatThrownBy(() -> resolver.resolveKgTopic(ROOT, outside, LEARNER))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("validation gate: non-VALIDATED content fails closed, indistinguishable from unresolvable")
    void unvalidatedTopicFailsClosed() {
        KnowledgeNode suggestedNode = org.mockito.Mockito.mock(KnowledgeNode.class);
        when(suggestedNode.validationStatus()).thenReturn(KnowledgeNode.ValidationStatus.SUGGESTED);
        when(nodes.findById(TOPIC)).thenReturn(Optional.of(suggestedNode));

        assertThatThrownBy(() -> resolver.resolveKgTopic(ROOT, TOPIC, LEARNER))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("validated");
    }

    @Test
    @DisplayName("fail-closed: a root that is not a subject root is a 404")
    void nonSubjectRootFailsClosed() {
        UUID unknownRoot = UUID.randomUUID();
        when(subjects.findByKnowledgeNodeId(unknownRoot)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveKgTopic(unknownRoot, TOPIC, LEARNER))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("subject root");
    }

    @Test
    @DisplayName("fail-closed: unresolvable topic inside the tree registry is a 404")
    void unknownTopicFailsClosed() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> resolver.resolveKgTopic(ROOT, unknown, LEARNER))
                .isInstanceOf(NotFoundException.class);
    }
}

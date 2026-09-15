package com.syllabai.cla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.QuestionPartRepository;
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
    private final QuestionPartRepository questionParts = mock(QuestionPartRepository.class);
    private final AttemptRepository attempts = mock(AttemptRepository.class);

    private final ClaContextResolver resolver = new ClaContextResolver(graph, nodes, subjects,
            questions, examPapers, questionVersions, questionParts, servableQuestions, attempts);

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
        when(topicNode.id()).thenReturn(TOPIC);
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

    // ── SPECIFICATION_POINT: the syllabus-browser anchor resolves by CODE ──

    @Test
    @DisplayName("SPECIFICATION_POINT resolves by spec-point code to a VALIDATED node")
    void resolvesSpecificationPointByCode() {
        ResourceContext context = resolver.resolveSpecificationPoint(
                ROOT, " IALCHEM2018-U1-T3 ", LEARNER);

        assertThat(context.kind()).isEqualTo(ResourceContext.Kind.SPECIFICATION_POINT);
        assertThat(context.reference()).isEqualTo(TOPIC);
        assertThat(context.topicNodeId()).isEqualTo(TOPIC);
        assertThat(context.topicCode()).isEqualTo("IALCHEM2018-U1-T3");
        assertThat(context.subjectCode()).isEqualTo("4CH1");
        assertThat(context.validationState()).isEqualTo("VALIDATED");
        // stripped, not guessed: the client's code is matched exactly (with trim)
        assertThat(context.curriculumVersion().code()).isEqualTo("IALCHEM2018");
    }

    @Test
    @DisplayName("SPECIFICATION_POINT fail-closed: unknown code is a 404, never a guess")
    void unknownSpecCodeFailsClosed() {
        assertThatThrownBy(() -> resolver.resolveSpecificationPoint(
                ROOT, "IALCHEM2018-U1-T99", LEARNER))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("SPECIFICATION_POINT fail-closed: another subject's spec code is a 404")
    void foreignSpecCodeFailsClosed() {
        // WCH11 codes belong to the CHM seed subject, never to this one
        assertThatThrownBy(() -> resolver.resolveSpecificationPoint(
                ROOT, "WCH11-T1.1", LEARNER))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("SPECIFICATION_POINT validation gate: a code on non-VALIDATED content fails closed")
    void unvalidatedSpecCodeFailsClosed() {
        // a SUGGESTED concept child carries a code inside the subtree; the
        // gate must still 404 it (indistinguishable from unknown)
        UUID conceptId = UUID.randomUUID();
        NodeView suggested = new NodeView(conceptId, "CONCEPT-x", "CONCEPT",
                "retrieval-graph concept", null, "SUGGESTED", null, List.of());
        NodeView topic = node(TOPIC, "IALCHEM2018-U1-T3", "TOPIC",
                "Bonding and structure", List.of(suggested));
        NodeView unit = node(UNIT, "IALCHEM2018-U1", "UNIT", "Unit 1", List.of(topic));
        when(graph.tree(ROOT)).thenReturn(
                node(ROOT, "IALCHEM2018", "SUBJECT", "IAL Chemistry", List.of(unit)));
        KnowledgeNode suggestedNode = org.mockito.Mockito.mock(KnowledgeNode.class);
        when(suggestedNode.validationStatus()).thenReturn(KnowledgeNode.ValidationStatus.SUGGESTED);
        when(nodes.findById(conceptId)).thenReturn(Optional.of(suggestedNode));

        assertThatThrownBy(() -> resolver.resolveSpecificationPoint(
                ROOT, "CONCEPT-x", LEARNER))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("validated");
    }

    // ── QUESTION_PART: the part-level anchor resolves through canonical FKs ──

    private static final String PART_LABEL = "a";

    private com.syllabai.assessment.QuestionPart partOnCurrentVersion(
            UUID partId, UUID questionId, UUID versionId, UUID paperId) {
        com.syllabai.assessment.QuestionPart part =
                mock(com.syllabai.assessment.QuestionPart.class);
        when(part.id()).thenReturn(partId);
        when(part.label()).thenReturn(PART_LABEL);
        when(part.prompt()).thenReturn("State why ionic compounds conduct when molten.");
        when(part.commandWord()).thenReturn("State");
        when(part.marks()).thenReturn(2);

        com.syllabai.assessment.QuestionVersion version =
                mock(com.syllabai.assessment.QuestionVersion.class);
        when(version.id()).thenReturn(versionId);
        when(version.questionId()).thenReturn(questionId);
        when(version.validationState())
                .thenReturn(com.syllabai.assessment.QuestionVersion.ValidationState.VALIDATED);
        when(version.marks()).thenReturn(6);
        when(part.questionVersion()).thenReturn(version);

        com.syllabai.assessment.Question question =
                mock(com.syllabai.assessment.Question.class);
        when(question.id()).thenReturn(questionId);
        when(question.active()).thenReturn(true);
        when(question.examPaperId()).thenReturn(paperId);
        when(question.primaryTopicNodeId()).thenReturn(TOPIC);
        when(question.commandWord()).thenReturn("Explain");
        when(questions.findById(questionId)).thenReturn(Optional.of(question));

        when(questionVersions.findByQuestionIdOrderByVersionDesc(questionId))
                .thenReturn(List.of(version));
        return part;
    }

    @Test
    @DisplayName("QUESTION_PART resolves through part → version → question → subject → topic")
    void resolvesQuestionPart() {
        UUID partId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID paperId = UUID.randomUUID();
        com.syllabai.assessment.QuestionPart part =
                partOnCurrentVersion(partId, questionId, versionId, paperId);
        when(questionParts.findById(partId)).thenReturn(Optional.of(part));

        com.syllabai.curriculum.Subject subject = mock(com.syllabai.curriculum.Subject.class);
        when(subject.id()).thenReturn(UUID.randomUUID());
        when(subject.code()).thenReturn("4CH1");
        when(subject.knowledgeNodeId()).thenReturn(ROOT);
        when(subject.curriculumVersion()).thenReturn(version);
        com.syllabai.assessment.ExamPaper paper = mock(com.syllabai.assessment.ExamPaper.class);
        when(paper.subjectId()).thenReturn(UUID.randomUUID());
        when(paper.paperCode()).thenReturn("4CH0/2C");
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        when(subjects.findById(paper.subjectId())).thenReturn(Optional.of(subject));
        when(attempts.existsByLearnerIdAndQuestionId(LEARNER, questionId)).thenReturn(false);
        when(servableQuestions.isServable(questionId)).thenReturn(true);

        ResourceContext context = resolver.resolveQuestionPart(partId, null, LEARNER);

        assertThat(context.kind()).isEqualTo(ResourceContext.Kind.QUESTION_PART);
        assertThat(context.reference()).isEqualTo(partId);
        assertThat(context.topicNodeId()).isEqualTo(TOPIC);
        assertThat(context.rootId()).isEqualTo(ROOT);
        assertThat(context.subjectCode()).isEqualTo("4CH1");
        assertThat(context.topicCode()).isEqualTo("IALCHEM2018-U1-T3");
        // the PART is what the learner is looking at: label, prompt, part marks
        assertThat(context.partLabel()).isEqualTo(PART_LABEL);
        assertThat(context.questionStem()).contains("ionic compounds conduct when molten");
        assertThat(context.questionMarks()).isEqualTo(2);
        assertThat(context.paperCode()).isEqualTo("4CH0/2C");
        assertThat(context.attempted()).isFalse();
        assertThat(context.validationState()).isEqualTo("VALIDATED");
        assertThat(context.curriculumVersion().code()).isEqualTo("IALCHEM2018");
    }

    @Test
    @DisplayName("QUESTION_PART fail-closed: unknown part is a 404, no existence oracle")
    void unknownPartFailsClosed() {
        assertThatThrownBy(() -> resolver.resolveQuestionPart(UUID.randomUUID(), null, LEARNER))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("QUESTION_PART relationship gate: a part of a superseded version is a 404")
    void partOfSupersededVersionFailsClosed() {
        UUID partId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID paperId = UUID.randomUUID();
        com.syllabai.assessment.QuestionPart part =
                partOnCurrentVersion(partId, questionId, UUID.randomUUID()/*its version*/, paperId);
        when(questionParts.findById(partId)).thenReturn(Optional.of(part));
        // the question's CURRENT version is a DIFFERENT version — relationship mismatch
        com.syllabai.assessment.QuestionVersion current =
                mock(com.syllabai.assessment.QuestionVersion.class);
        when(current.id()).thenReturn(UUID.randomUUID());
        when(questionVersions.findByQuestionIdOrderByVersionDesc(questionId))
                .thenReturn(List.of(current));

        assertThatThrownBy(() -> resolver.resolveQuestionPart(partId, null, LEARNER))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("current version");
    }

    @Test
    @DisplayName("QUESTION_PART fail-closed: unservable question is an indistinguishable 404")
    void partOfUnservableQuestionFailsClosed() {
        UUID partId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID paperId = UUID.randomUUID();
        com.syllabai.assessment.QuestionPart part =
                partOnCurrentVersion(partId, questionId, versionId, paperId);
        when(questionParts.findById(partId)).thenReturn(Optional.of(part));
        when(servableQuestions.isServable(questionId)).thenReturn(false);

        assertThatThrownBy(() -> resolver.resolveQuestionPart(partId, null, LEARNER))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("QUESTION_PART fail-closed: a root of ANOTHER subject is a foreign-subject 404")
    void partWithForeignRootFailsClosed() {
        UUID partId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID paperId = UUID.randomUUID();
        UUID ownSubjectId = UUID.randomUUID();
        com.syllabai.assessment.QuestionPart part =
                partOnCurrentVersion(partId, questionId, versionId, paperId);
        when(questionParts.findById(partId)).thenReturn(Optional.of(part));
        when(servableQuestions.isServable(questionId)).thenReturn(true);

        com.syllabai.curriculum.Subject own = mock(com.syllabai.curriculum.Subject.class);
        when(own.id()).thenReturn(ownSubjectId);
        when(own.knowledgeNodeId()).thenReturn(ROOT);
        com.syllabai.assessment.ExamPaper paper = mock(com.syllabai.assessment.ExamPaper.class);
        when(paper.subjectId()).thenReturn(UUID.randomUUID());
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        when(subjects.findById(paper.subjectId())).thenReturn(Optional.of(own));

        // the client's root belongs to a DIFFERENT subject — the mismatch is a 404
        com.syllabai.curriculum.Subject other = mock(com.syllabai.curriculum.Subject.class);
        when(other.id()).thenReturn(UUID.randomUUID());
        when(subjects.findByKnowledgeNodeId(ROOT)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> resolver.resolveQuestionPart(partId, ROOT, LEARNER))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("in this subject");
    }

    @Test
    @DisplayName("QUESTION_PART fail-closed: a supplied root that is no subject is a 404")
    void partWithUnknownRootFailsClosed() {
        UUID partId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID paperId = UUID.randomUUID();
        com.syllabai.assessment.QuestionPart part =
                partOnCurrentVersion(partId, questionId, versionId, paperId);
        when(questionParts.findById(partId)).thenReturn(Optional.of(part));
        when(servableQuestions.isServable(questionId)).thenReturn(true);

        // the anchor spine must resolve (the root check happens after it)
        com.syllabai.curriculum.Subject own = mock(com.syllabai.curriculum.Subject.class);
        when(own.id()).thenReturn(UUID.randomUUID());
        when(own.knowledgeNodeId()).thenReturn(ROOT);
        com.syllabai.assessment.ExamPaper paper = mock(com.syllabai.assessment.ExamPaper.class);
        when(paper.subjectId()).thenReturn(UUID.randomUUID());
        when(examPapers.findById(paperId)).thenReturn(Optional.of(paper));
        when(subjects.findById(paper.subjectId())).thenReturn(Optional.of(own));

        UUID unknownRoot = UUID.randomUUID();
        when(subjects.findByKnowledgeNodeId(unknownRoot)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveQuestionPart(partId, unknownRoot, LEARNER))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("subject root");
    }
}

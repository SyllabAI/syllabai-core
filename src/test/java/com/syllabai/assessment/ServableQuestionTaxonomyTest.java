package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.dto.QuestionTopicTaxonomyView;
import com.syllabai.knowledge.KnowledgeEdge;
import com.syllabai.knowledge.KnowledgeEdgeRepository;
import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.knowledge.KnowledgeNodeRepository;
import com.syllabai.knowledge.NodeType;
import com.syllabai.knowledge.RelationType;
import com.syllabai.sme.SmeQuestionSpecPointRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The servable-question taxonomy (session-112): counts must follow the SAME
 * rule as topic serving — a topic's count is the length of the list
 * {@code GET /api/v1/questions?topicNodeId=} returns — under the same
 * servability boundary (active + MCQ or validated STRUCTURED current version +
 * paper integrity gate) and the same topic reachability (PRIMARY mapping or
 * any secondary question_topics mapping, deduped per question).
 *
 * <p>Structure rides the knowledge graph: topics group under their PART_OF
 * parent (the section). The 4CH1 seed wrote DUPLICATE structural PART_OF
 * edges — grouping must dedup them and stay deterministic.</p>
 */
class ServableQuestionTaxonomyTest {

    private final QuestionRepository questions = mock(QuestionRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final SmeQuestionSpecPointRepository specPoints =
            mock(SmeQuestionSpecPointRepository.class);
    private final QuestionTopicRepository topicMappings = mock(QuestionTopicRepository.class);
    private final KnowledgeNodeRepository knowledgeNodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository knowledgeEdges = mock(KnowledgeEdgeRepository.class);
    private final KnowledgeGraphService knowledgeGraph = mock(KnowledgeGraphService.class);
    private final ServableQuestionService service =
            new ServableQuestionService(questions, questionVersions, examPapers, specPoints,
                    topicMappings, knowledgeNodes, knowledgeEdges, knowledgeGraph);

    // ── fixture helpers ───────────────────────────────────────────────────

    private Question mcq(UUID primaryTopicNodeId) {
        Question question = mock(Question.class);
        when(question.id()).thenReturn(UUID.randomUUID());
        when(question.active()).thenReturn(true);
        when(question.type()).thenReturn(Question.Type.MCQ_SINGLE);
        when(question.examPaperId()).thenReturn(null);
        when(question.primaryTopicNodeId()).thenReturn(primaryTopicNodeId);
        return question;
    }

    private Question structured(UUID paperId, UUID primaryTopicNodeId) {
        Question question = mock(Question.class);
        when(question.id()).thenReturn(UUID.randomUUID());
        when(question.active()).thenReturn(true);
        when(question.type()).thenReturn(Question.Type.STRUCTURED);
        when(question.examPaperId()).thenReturn(paperId);
        when(question.primaryTopicNodeId()).thenReturn(primaryTopicNodeId);
        return question;
    }

    /** register a VALIDATED current version for the question on the batched path */
    private void validatedCurrentVersion(Question question) {
        UUID questionId = question.id(); // hoisted: never touch another mock mid-stubbing
        QuestionVersion version = mock(QuestionVersion.class);
        when(version.questionId()).thenReturn(questionId);
        when(version.version()).thenReturn(1);
        when(version.validationState()).thenReturn(QuestionVersion.ValidationState.VALIDATED);
        when(version.parts()).thenReturn(List.of());
        when(questionVersions.findWithPartsByQuestionIdsIn(anyCollection()))
                .thenReturn(List.of(version));
    }

    private KnowledgeNode node(UUID id, String code, String title, NodeType type) {
        KnowledgeNode n = mock(KnowledgeNode.class);
        when(n.id()).thenReturn(id);
        when(n.code()).thenReturn(code);
        when(n.title()).thenReturn(title);
        when(n.nodeType()).thenReturn(type);
        return n;
    }

    private KnowledgeEdge partOf(UUID childId, UUID parentId) {
        KnowledgeEdge edge = mock(KnowledgeEdge.class);
        when(edge.sourceId()).thenReturn(childId);
        when(edge.targetId()).thenReturn(parentId);
        when(edge.relationType()).thenReturn(RelationType.PART_OF);
        return edge;
    }

    /** wire the KG metadata for the given sections/topics + explicit PART_OF pairs */
    private void wireGraph(List<KnowledgeNode> sections, List<KnowledgeNode> topics,
                           List<Map.Entry<UUID, UUID>> topicToSection) {
        List<KnowledgeEdge> edges = new java.util.ArrayList<>();
        for (Map.Entry<UUID, UUID> pair : topicToSection) {
            // duplicate structural edge on purpose: the seed wrote several
            edges.add(partOf(pair.getKey(), pair.getValue()));
            edges.add(partOf(pair.getKey(), pair.getValue()));
        }
        Map<UUID, KnowledgeNode> byId = new HashMap<>();
        sections.forEach(s -> byId.put(s.id(), s));
        topics.forEach(t -> byId.put(t.id(), t));
        // evaluate every mock accessor BEFORE any when(): Mockito treats mock
        // calls inside thenReturn(...) argument evaluation as unfinished stubbing
        List<KnowledgeEdge> wiredEdges = edges.stream()
                .filter(e -> byId.containsKey(e.targetId()))
                .toList();
        when(knowledgeNodes.findAllById(anyCollection()))
                .thenAnswer(inv -> {
                    List<KnowledgeNode> found = new java.util.ArrayList<>();
                    for (Object id : inv.getArgument(0, Iterable.class)) {
                        KnowledgeNode n = byId.get((UUID) id);
                        if (n != null) {
                            found.add(n);
                        }
                    }
                    return found;
                });
        when(knowledgeEdges.findPartOfEdgesFrom(anyCollection())).thenReturn(wiredEdges);
    }

    private static Map.Entry<UUID, UUID> under(UUID topicId, UUID sectionId) {
        return Map.entry(topicId, sectionId);
    }

    private Optional<QuestionTopicTaxonomyView.Topic> find(
            QuestionTopicTaxonomyView view, String code) {
        return view.sections().stream()
                .flatMap(s -> s.topics().stream())
                .filter(t -> t.code().equals(code))
                .findFirst();
    }

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a topic's count is the length of its serving list: primary + secondary, deduped")
    void countsMatchServingReachability() {
        UUID sectionId = UUID.randomUUID();
        UUID topicA = UUID.randomUUID();
        UUID topicB = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());

        Question mcqA = mcq(topicA);                        // MCQ, primary A
        Question structuredAB = structured(null, topicA);   // structured, primary A
        Question mcqB = mcq(topicB);                        // MCQ, primary B
        when(questions.findAllActive()).thenReturn(List.of(mcqA, structuredAB, mcqB));
        validatedCurrentVersion(structuredAB);
        // secondary mappings: structuredAB also reachable from B; mcqB ALSO from A
        // (a duplicate primary row is included on purpose — must dedup to one count)
        when(topicMappings.findByQuestionIdIn(anyCollection())).thenReturn(List.of(
                new QuestionTopic(structuredAB, topicB, false),
                new QuestionTopic(mcqB, topicA, false),
                new QuestionTopic(mcqB, topicB, true)));

        wireGraph(
                List.of(node(sectionId, "4CH1-S1", "Principles of chemistry", NodeType.UNIT)),
                List.of(node(topicA, "4CH1-S1-a", "States of matter", NodeType.TOPIC),
                        node(topicB, "4CH1-S1-b", "Elements, compounds", NodeType.TOPIC)),
                List.of(under(topicA, sectionId), under(topicB, sectionId)));

        QuestionTopicTaxonomyView view = service.taxonomy(null);

        assertThat(view.sections()).hasSize(1);
        QuestionTopicTaxonomyView.Section section = view.sections().get(0);
        assertThat(section.code()).isEqualTo("4CH1-S1");
        assertThat(section.topics()).extracting(QuestionTopicTaxonomyView.Topic::code)
                .containsExactly("4CH1-S1-a", "4CH1-S1-b"); // code-ordered

        QuestionTopicTaxonomyView.Topic a = find(view, "4CH1-S1-a").orElseThrow();
        // mcqA (primary) + structuredAB (primary) + mcqB (secondary) — and mcqB's
        // duplicate PRIMARY-mapped row under its own topic does not double-count A
        assertThat(a.questionCount()).isEqualTo(3);
        assertThat(a.mcqCount()).isEqualTo(2);
        assertThat(a.structuredCount()).isEqualTo(1);

        QuestionTopicTaxonomyView.Topic b = find(view, "4CH1-S1-b").orElseThrow();
        // mcqB (primary, once despite the duplicate row) + structuredAB (secondary)
        assertThat(b.questionCount()).isEqualTo(2);
        assertThat(b.mcqCount()).isEqualTo(1);
        assertThat(b.structuredCount()).isEqualTo(1);

        // session-116 deduped census: the badges sum to 5 because mcqB and
        // structuredAB each appear under two topics — but the section and view
        // totals count each DISTINCT question once (3), which is what the
        // sidebar total renders
        assertThat(section.distinctQuestionCount()).isEqualTo(3);
        assertThat(view.totalDistinctQuestions()).isEqualTo(3);
    }

    @Test
    @DisplayName("a question spanning two sections counts once per section, once in the total")
    void crossSectionQuestionDeduped() {
        UUID section1 = UUID.randomUUID();
        UUID section2 = UUID.randomUUID();
        UUID topicInS1 = UUID.randomUUID();
        UUID topicInS2 = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());

        Question crossSection = mcq(topicInS1);              // primary in S1
        Question s2Only = mcq(topicInS2);                     // primary in S2
        when(questions.findAllActive()).thenReturn(List.of(crossSection, s2Only));
        // crossSection also reachable from the S2 topic (secondary mapping)
        when(topicMappings.findByQuestionIdIn(anyCollection())).thenReturn(List.of(
                new QuestionTopic(crossSection, topicInS2, false)));

        wireGraph(
                List.of(node(section1, "4CH1-S1", "Principles of chemistry", NodeType.UNIT),
                        node(section2, "4CH1-S2", "Inorganic chemistry", NodeType.UNIT)),
                List.of(node(topicInS1, "4CH1-S1-a", "States of matter", NodeType.TOPIC),
                        node(topicInS2, "4CH1-S2-b", "Group 1", NodeType.TOPIC)),
                List.of(under(topicInS1, section1), under(topicInS2, section2)));

        QuestionTopicTaxonomyView view = service.taxonomy(null);

        // per-topic badges stay reachable counts: crossSection counts under BOTH topics
        assertThat(find(view, "4CH1-S1-a").orElseThrow().questionCount()).isEqualTo(1);
        assertThat(find(view, "4CH1-S2-b").orElseThrow().questionCount()).isEqualTo(2);
        // each section dedupes within itself: S2 holds {crossSection, s2Only} = 2
        assertThat(view.sections()).extracting(
                        QuestionTopicTaxonomyView.Section::distinctQuestionCount)
                .containsExactly(1, 2); // code-ordered S1, S2
        // the view total counts crossSection ONCE, globally: {crossSection, s2Only}
        assertThat(view.totalDistinctQuestions()).isEqualTo(2);
    }

    @Test
    @DisplayName("a STRUCTURED question whose current version is not VALIDATED does not count")
    void unvalidatedStructuredExcluded() {
        UUID topicId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());
        Question unvalidated = structured(null, topicId);
        Question mcq = mcq(topicId);
        when(questions.findAllActive()).thenReturn(List.of(unvalidated, mcq));

        UUID questionId = unvalidated.id();
        QuestionVersion suggested = mock(QuestionVersion.class);
        when(suggested.questionId()).thenReturn(questionId);
        when(suggested.version()).thenReturn(1);
        when(suggested.validationState()).thenReturn(QuestionVersion.ValidationState.SUGGESTED);
        when(suggested.parts()).thenReturn(List.of());
        when(questionVersions.findWithPartsByQuestionIdsIn(anyCollection()))
                .thenReturn(List.of(suggested));
        when(topicMappings.findByQuestionIdIn(anyCollection())).thenReturn(List.of());

        wireGraph(List.of(node(sectionId, "4CH1-S1", "Principles of chemistry", NodeType.UNIT)),
                List.of(node(topicId, "4CH1-S1-a", "States of matter", NodeType.TOPIC)),
                List.of(under(topicId, sectionId)));

        QuestionTopicTaxonomyView view = service.taxonomy(null);
        QuestionTopicTaxonomyView.Topic topic = find(view, "4CH1-S1-a").orElseThrow();
        assertThat(topic.questionCount()).isEqualTo(1); // the MCQ only
        assertThat(topic.structuredCount()).isZero();
    }

    @Test
    @DisplayName("a question under a REJECTED paper does not count (V20 paper gate)")
    void rejectedPaperExcluded() {
        UUID topicId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID rejectedPaper = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of(rejectedPaper));
        Question blocked = structured(rejectedPaper, topicId);
        when(questions.findAllActive()).thenReturn(List.of(blocked));
        validatedCurrentVersion(blocked);
        when(topicMappings.findByQuestionIdIn(anyCollection())).thenReturn(List.of());

        wireGraph(List.of(node(sectionId, "4CH1-S1", "Principles of chemistry", NodeType.UNIT)),
                List.of(node(topicId, "4CH1-S1-a", "States of matter", NodeType.TOPIC)),
                List.of(under(topicId, sectionId)));

        // nothing servable -> no sections at all (the taxonomy is what can be practised)
        assertThat(service.taxonomy(null).sections()).isEmpty();
    }

    @Test
    @DisplayName("rootId scopes the taxonomy to the subject subtree")
    void rootIdScopesTopics() {
        UUID rootId = UUID.randomUUID();
        UUID insideSection = UUID.randomUUID();
        UUID insideTopic = UUID.randomUUID();
        UUID outsideTopic = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());
        when(knowledgeGraph.subtreeIds(rootId)).thenReturn(List.of(rootId, insideSection, insideTopic));

        Question inside = mcq(insideTopic);
        Question outside = mcq(outsideTopic);
        when(questions.findAllActive()).thenReturn(List.of(inside, outside));
        when(topicMappings.findByQuestionIdIn(anyCollection())).thenReturn(List.of());

        wireGraph(List.of(node(insideSection, "4CH1-S1", "Principles of chemistry", NodeType.UNIT)),
                List.of(node(insideTopic, "4CH1-S1-a", "States of matter", NodeType.TOPIC),
                        node(outsideTopic, "WCH11-T3.1", "Foreign topic", NodeType.TOPIC)),
                List.of(under(insideTopic, insideSection)));

        QuestionTopicTaxonomyView view = service.taxonomy(rootId);
        assertThat(view.sections()).hasSize(1);
        assertThat(view.sections().get(0).topics()).hasSize(1);
        assertThat(view.sections().get(0).topics().get(0).code()).isEqualTo("4CH1-S1-a");
    }

    @Test
    @DisplayName("sections and topics are code-ordered; a parentless topic is not browsable")
    void orderingAndParentlessSkipped() {
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        UUID topicD = UUID.randomUUID();
        UUID topicC = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());
        Question qD = mcq(topicD);
        Question qC = mcq(topicC);
        Question qOrphan = mcq(orphan);
        when(questions.findAllActive()).thenReturn(List.of(qD, qC, qOrphan));
        when(topicMappings.findByQuestionIdIn(anyCollection())).thenReturn(List.of());

        KnowledgeNode section1 = node(s1, "4CH1-S2", "Inorganic chemistry", NodeType.UNIT);
        KnowledgeNode section2 = node(s2, "4CH1-S3", "Physical chemistry", NodeType.UNIT);
        KnowledgeNode topicCNode = node(topicC, "4CH1-S3-c", "Equilibria", NodeType.TOPIC);
        KnowledgeNode topicDNode = node(topicD, "4CH1-S2-d", "Reactivity series", NodeType.TOPIC);
        KnowledgeNode orphanNode = node(orphan, "4CH1-S9-z", "No parent", NodeType.TOPIC);
        // feed sections out of code order: the response must still be code-ordered.
        // orphan gets NO PART_OF edge — it must be skipped entirely.
        wireGraph(List.of(section2, section1), List.of(topicDNode, topicCNode, orphanNode),
                List.of(under(topicD, s1), under(topicC, s2)));

        QuestionTopicTaxonomyView view = service.taxonomy(null);
        assertThat(view.sections()).extracting(QuestionTopicTaxonomyView.Section::code)
                .containsExactly("4CH1-S2", "4CH1-S3");
        assertThat(view.sections().get(0).topics())
                .extracting(QuestionTopicTaxonomyView.Topic::code)
                .containsExactly("4CH1-S2-d");
        assertThat(view.sections().get(1).topics())
                .extracting(QuestionTopicTaxonomyView.Topic::code)
                .containsExactly("4CH1-S3-c");
        assertThat(find(view, "4CH1-S9-z")).isEmpty();
    }
}

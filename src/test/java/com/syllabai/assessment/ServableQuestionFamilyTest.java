package com.syllabai.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.dto.QuestionFamilyView;
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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole-question family read model (session-121) must ride the SAME
 * servability boundary and scoping as the flat list it replaces for learner
 * surfaces — and the taxonomy's family census must equal the family list
 * length per topic (the demo sidebar's click invariant, at family granularity).
 */
class ServableQuestionFamilyTest {

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

    /** a full MCQ row of an SME family (externalRef carries the part suffix) */
    private Question smeMcq(String externalRef, int marks, UUID primaryTopicNodeId) {
        Question question = mock(Question.class);
        when(question.id()).thenReturn(UUID.randomUUID());
        when(question.active()).thenReturn(true);
        when(question.type()).thenReturn(Question.Type.MCQ_SINGLE);
        when(question.examPaperId()).thenReturn(null);
        when(question.primaryTopicNodeId()).thenReturn(primaryTopicNodeId);
        when(question.externalRef()).thenReturn(externalRef);
        when(question.stem()).thenReturn("stem of " + externalRef);
        when(question.marks()).thenReturn(marks);
        when(question.difficulty()).thenReturn(2);
        when(question.expectedTimeSeconds()).thenReturn(60);
        when(question.commandWord()).thenReturn("State");
        when(question.options()).thenReturn(List.of());
        return question;
    }

    /** the structured section row of a mixed SME family */
    private Question smeStructured(String externalRef, int marks, UUID primaryTopicNodeId,
                                   QuestionVersion.ValidationState state) {
        Question question = mock(Question.class);
        when(question.id()).thenReturn(UUID.randomUUID());
        when(question.active()).thenReturn(true);
        when(question.type()).thenReturn(Question.Type.STRUCTURED);
        when(question.examPaperId()).thenReturn(null);
        when(question.primaryTopicNodeId()).thenReturn(primaryTopicNodeId);
        when(question.externalRef()).thenReturn(externalRef);
        when(question.stem()).thenReturn("stem of " + externalRef);
        when(question.marks()).thenReturn(marks);
        when(question.difficulty()).thenReturn(3);
        when(question.expectedTimeSeconds()).thenReturn(120);
        when(question.commandWord()).thenReturn("Explain");
        return question;
    }

    /** register the question's current version (VALIDATED or not) on the batched path */
    private void currentVersion(Question question, int marks,
                                QuestionVersion.ValidationState state) {
        UUID questionId = question.id(); // hoisted: never touch another mock mid-stubbing
        QuestionVersion version = mock(QuestionVersion.class);
        when(version.questionId()).thenReturn(questionId);
        when(version.version()).thenReturn(1);
        when(version.validationState()).thenReturn(state);
        when(version.stem()).thenReturn("version stem");
        when(version.marks()).thenReturn(marks);
        when(version.difficulty()).thenReturn(3);
        when(version.expectedTimeSeconds()).thenReturn(120);
        when(version.commandWord()).thenReturn("Explain");
        QuestionPart part = mock(QuestionPart.class);
        when(part.id()).thenReturn(UUID.randomUUID());
        when(part.label()).thenReturn("a");
        when(part.prompt()).thenReturn("why?");
        when(part.commandWord()).thenReturn("Explain");
        when(part.marks()).thenReturn(marks);
        when(version.parts()).thenReturn(List.of(part));
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

    private void wireGraph(List<KnowledgeNode> sections, List<KnowledgeNode> topics,
                           List<Map.Entry<UUID, UUID>> topicToSection) {
        List<KnowledgeEdge> edges = new java.util.ArrayList<>();
        for (Map.Entry<UUID, UUID> pair : topicToSection) {
            edges.add(partOf(pair.getKey(), pair.getValue()));
        }
        Map<UUID, KnowledgeNode> byId = new HashMap<>();
        sections.forEach(s -> byId.put(s.id(), s));
        topics.forEach(t -> byId.put(t.id(), t));
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

    // ── tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a topic's family list reassembles the split import into whole SME questions")
    void familiesByTopicServeWholeQuestions() {
        UUID topicId = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());
        // the session-120 production defect family: ionic bonding q1, four MCQ
        // part rows — the topic list must serve it as ONE four-part question
        Question p1 = smeMcq("sme-eq-1-6-ionic-bonding-q1-p1", 1, topicId);
        Question p2 = smeMcq("sme-eq-1-6-ionic-bonding-q1-p2", 1, topicId);
        Question p3 = smeMcq("sme-eq-1-6-ionic-bonding-q1-p3", 1, topicId);
        Question p4 = smeMcq("sme-eq-1-6-ionic-bonding-q1-p4", 3, topicId);
        // plus a mixed question: MCQ parts + validated structured twin
        Question mp1 = smeMcq("sme-eq-1-8-metallic-bonding-q3-p1", 1, topicId);
        Question mp2 = smeMcq("sme-eq-1-8-metallic-bonding-q3-p2", 1, topicId);
        Question ms = smeStructured("sme-eq-1-8-metallic-bonding-q3-s", 2, topicId,
                QuestionVersion.ValidationState.VALIDATED);
        currentVersion(ms, 2, QuestionVersion.ValidationState.VALIDATED);
        when(questions.findActiveByTopic(topicId))
                .thenReturn(List.of(p4, p1, ms, p3, mp1, mp2, p2)); // shuffled on purpose

        List<QuestionFamilyView> units = service.familiesByTopic(topicId);

        assertThat(units).hasSize(2);
        assertThat(units).extracting(QuestionFamilyView::key).containsExactly(
                "sme-eq-1-6-ionic-bonding-q1", "sme-eq-1-8-metallic-bonding-q3"); // page order

        QuestionFamilyView ionic = units.get(0);
        assertThat(ionic.marks()).isEqualTo(6);
        assertThat(ionic.type()).isEqualTo("MCQ");
        assertThat(ionic.parts()).hasSize(4);

        // the pinned interleaved order for this family: -s, -p1, -p2
        QuestionFamilyView metallic = units.get(1);
        assertThat(metallic.type()).isEqualTo("STRUCTURED");
        assertThat(metallic.parts()).extracting(part -> part.externalRef())
                .containsExactly("sme-eq-1-8-metallic-bonding-q3-s",
                        "sme-eq-1-8-metallic-bonding-q3-p1",
                        "sme-eq-1-8-metallic-bonding-q3-p2");
        // the structured twin's parts survive the projection (sub-questions intact)
        assertThat(metallic.parts().get(0).parts()).hasSize(1);
    }

    @Test
    @DisplayName("the servability boundary applies per row: an unvalidated -s twin drops out, the family stays")
    void unvalidatedStructuredTwinDropsOut() {
        UUID topicId = UUID.randomUUID();
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());
        Question p1 = smeMcq("sme-eq-1-8-metallic-bonding-q3-p1", 1, topicId);
        Question p2 = smeMcq("sme-eq-1-8-metallic-bonding-q3-p2", 1, topicId);
        Question unvalidatedS = smeStructured("sme-eq-1-8-metallic-bonding-q3-s", 2, topicId,
                QuestionVersion.ValidationState.SUGGESTED);
        currentVersion(unvalidatedS, 2, QuestionVersion.ValidationState.SUGGESTED);
        when(questions.findActiveByTopic(topicId)).thenReturn(List.of(p1, unvalidatedS, p2));

        List<QuestionFamilyView> units = service.familiesByTopic(topicId);

        assertThat(units).hasSize(1);
        assertThat(units.get(0).parts()).extracting(part -> part.externalRef())
                .containsExactly("sme-eq-1-8-metallic-bonding-q3-p1",
                        "sme-eq-1-8-metallic-bonding-q3-p2"); // -s never serves
        assertThat(units.get(0).type()).isEqualTo("MCQ"); // only MCQ members left
    }

    @Test
    @DisplayName("a topic's familyCount is exactly its family list length, and the family census dedupes like the row census")
    void taxonomyFamilyCountParity() {
        UUID section1 = UUID.randomUUID();
        UUID section2 = UUID.randomUUID();
        UUID topicA = UUID.randomUUID(); // in S1
        UUID topicB = UUID.randomUUID(); // in S2
        when(examPapers.findIdsBlockingServing()).thenReturn(List.of());

        // family F: two part rows, primary on topicA, ALSO secondary on topicB
        Question f1 = smeMcq("sme-eq-1-1-states-of-matter-q16-p1", 1, topicA);
        Question f2 = smeMcq("sme-eq-1-1-states-of-matter-q16-p2", 1, topicA);
        // a standalone MCQ row on topicB
        Question solo = smeMcq("WCH11-2022-01-03a", 2, topicB);
        when(questions.findAllActive()).thenReturn(List.of(f1, f2, solo));
        when(questions.findActiveByTopic(topicA)).thenReturn(List.of(f1, f2));
        when(questions.findActiveByTopic(topicB)).thenReturn(List.of(f1, f2, solo));
        when(topicMappings.findByQuestionIdIn(anyCollection())).thenReturn(List.of(
                new QuestionTopic(f1, topicB, false),  // family F also reachable from B
                new QuestionTopic(f2, topicB, false)));
        when(specPoints.findCodesByQuestionIdsIn(anyCollection())).thenReturn(List.of());

        wireGraph(
                List.of(node(section1, "4CH1-S1", "Principles of chemistry", NodeType.UNIT),
                        node(section2, "4CH1-S2", "Inorganic chemistry", NodeType.UNIT)),
                List.of(node(topicA, "4CH1-S1-a", "States of matter", NodeType.TOPIC),
                        node(topicB, "4CH1-S2-b", "Group 1", NodeType.TOPIC)),
                List.of(under(topicA, section1), under(topicB, section2)));

        QuestionTopicTaxonomyView view = service.taxonomy(null);

        QuestionTopicTaxonomyView.Topic a = view.sections().get(0).topics().get(0);
        QuestionTopicTaxonomyView.Topic b = view.sections().get(1).topics().get(0);

        // the click invariant, at family granularity: badge == list length
        assertThat(a.familyCount()).isEqualTo(1);
        assertThat(service.familiesByTopic(topicA)).hasSize(1);
        assertThat(b.familyCount()).isEqualTo(2);
        assertThat(service.familiesByTopic(topicB)).hasSize(2);

        // the row census is unchanged (session-116 semantics preserved)
        assertThat(a.questionCount()).isEqualTo(2);
        assertThat(b.questionCount()).isEqualTo(3);

        // the deduped family census mirrors the row census: family F once per
        // section it appears in, once in the view total
        assertThat(view.sections()).extracting(
                        QuestionTopicTaxonomyView.Section::distinctFamilyCount)
                .containsExactly(1, 2); // S1 holds {F}; S2 holds {F, solo}
        assertThat(view.totalDistinctFamilies()).isEqualTo(2);
        // …and the row totals keep their session-116 meaning
        assertThat(view.totalDistinctQuestions()).isEqualTo(3);
    }
}

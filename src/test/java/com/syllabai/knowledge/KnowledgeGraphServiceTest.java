package com.syllabai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.knowledge.dto.NodeView;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The batched tree builder must produce EXACTLY the shape the old per-node
 * recursion produced (ordering, misconception attachment rules, dedupe) while
 * collapsing ~2N queries into three bulk queries — the 4CH1 personalized read
 * model ran ~800 round-trips and blew past the platform's proxy timeout.
 */
class KnowledgeGraphServiceTest {

    private final KnowledgeNodeRepository nodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository edges = mock(KnowledgeEdgeRepository.class);
    private final KnowledgeGraphRepository graph = mock(KnowledgeGraphRepository.class);
    private final KnowledgeGraphService service = new KnowledgeGraphService(nodes, edges, graph);

    private KnowledgeNode node(String code, NodeType type) {
        KnowledgeNode n = mock(KnowledgeNode.class);
        UUID id = UUID.nameUUIDFromBytes(code.getBytes());
        when(n.id()).thenReturn(id);
        when(n.code()).thenReturn(code);
        when(n.nodeType()).thenReturn(type);
        when(n.title()).thenReturn("title " + code);
        when(n.description()).thenReturn("desc");
        when(n.validationStatus()).thenReturn(KnowledgeNode.ValidationStatus.VALIDATED);
        when(n.provenance()).thenReturn(null);
        return n;
    }

    @Test
    @DisplayName("batched tree mirrors the per-node walk: order, attachment rules, dedupe")
    void batchedTreeMatchesWalkSemantics() {
        KnowledgeNode root = node("S1", NodeType.SUBJECT);
        KnowledgeNode unit = node("U1", NodeType.UNIT);
        KnowledgeNode topicB = node("T2", NodeType.TOPIC);
        KnowledgeNode topicA = node("T1", NodeType.TOPIC);
        KnowledgeNode concept = node("C1", NodeType.CONCEPT);
        KnowledgeNode misOfTopic = node("M1", NodeType.MISCONCEPTION);
        KnowledgeNode misRemediated = node("M2", NodeType.MISCONCEPTION);

        when(nodes.findById(root.id())).thenReturn(java.util.Optional.of(root));
        when(graph.findSubtree(root.id())).thenReturn(List.of(
                root, unit, topicA, topicB, concept, misRemediated));   // misconceptions NOT in subtree

        KnowledgeEdge partUnit = mock(KnowledgeEdge.class);
        stubEdge(partUnit, unit, root, RelationType.PART_OF);
        KnowledgeEdge partTopicA = mock(KnowledgeEdge.class);
        stubEdge(partTopicA, topicA, unit, RelationType.PART_OF);
        KnowledgeEdge partTopicB = mock(KnowledgeEdge.class);
        stubEdge(partTopicB, topicB, unit, RelationType.PART_OF);
        KnowledgeEdge partConcept = mock(KnowledgeEdge.class);
        stubEdge(partConcept, concept, unit, RelationType.PART_OF);
        when(edges.findPartOfEdgesWithin(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(partUnit, partConcept, partTopicB, partTopicA));

        KnowledgeEdge misToTopicA = mock(KnowledgeEdge.class);
        stubEdge(misToTopicA, misOfTopic, topicA, RelationType.MISCONCEPTION_OF);
        KnowledgeEdge remToConcept = mock(KnowledgeEdge.class);
        stubEdge(remToConcept, misRemediated, concept, RelationType.REMEDIATED_BY);
        KnowledgeEdge wapToConcept = mock(KnowledgeEdge.class);
        stubEdge(wapToConcept, misRemediated, concept, RelationType.WRONG_ANSWER_PATTERN);
        when(edges.findMisconceptionFamilyEdgesWithin(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(misToTopicA, wapToConcept, remToConcept));

        NodeView tree = service.treeWithMisconceptions(root.id());

        // shape: root → unit → (T1, T2, C1) code-ordered; T1 carries M1; C1 carries M2 once
        assertThat(tree.children()).hasSize(1);
        NodeView unitView = tree.children().get(0);
        assertThat(unitView.children()).extracting(NodeView::code)
                .containsExactly("C1", "T1", "T2");   // PART_OF children first, source-code ordered (SQL parity)
        NodeView t1 = unitView.children().stream().filter(n -> n.code().equals("T1")).findFirst().orElseThrow();
        NodeView t2 = unitView.children().stream().filter(n -> n.code().equals("T2")).findFirst().orElseThrow();
        NodeView c1 = unitView.children().stream().filter(n -> n.code().equals("C1")).findFirst().orElseThrow();
        assertThat(t1.children()).extracting(NodeView::code).containsExactly("M1");
        assertThat(t2.children()).isEmpty();
        assertThat(c1.children()).extracting(NodeView::code).containsExactly("M2");   // deduped

        // plain tree: same structure, no misconception children
        when(edges.findPartOfEdgesWithin(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(partUnit, partConcept, partTopicB, partTopicA));
        NodeView plain = service.tree(root.id());
        assertThat(plain.children().get(0).children()).extracting(NodeView::code)
                .containsExactly("C1", "T1", "T2");
        assertThat(plain.children().get(0).children().get(0).children()).isEmpty();
    }

    private void stubEdge(KnowledgeEdge e, KnowledgeNode source, KnowledgeNode target,
                          RelationType type) {
        UUID sourceId = source.id();
        UUID targetId = target.id();
        when(e.source()).thenReturn(source);
        when(e.target()).thenReturn(target);
        when(e.sourceId()).thenReturn(sourceId);
        when(e.targetId()).thenReturn(targetId);
        when(e.relationType()).thenReturn(type);
    }

    @Test
    @DisplayName("prerequisiteRelations honors the edge convention: source=dependent, target=prerequisite")
    void prerequisiteRelationsMapEdgeDirection() {
        // V6 seed + T-C11 authored graph both store REQUIRES_PREREQUISITE as
        // (source = the DEPENDENT node, target = the PREREQUISITE it needs).
        // The mastery map / NBA T2 consume (prerequisiteId, dependentNodeId) —
        // a swap here inverts remediation advice silently (P8 live finding).
        KnowledgeNode root = node("S1", NodeType.SUBJECT);
        KnowledgeNode dependent = node("C-DEPENDENT", NodeType.CONCEPT);
        KnowledgeNode prerequisite = node("C-PREREQ", NodeType.CONCEPT);
        when(nodes.findById(root.id())).thenReturn(java.util.Optional.of(root));
        when(graph.findSubtree(root.id())).thenReturn(List.of(root, dependent, prerequisite));

        KnowledgeEdge requires = mock(KnowledgeEdge.class);
        stubEdge(requires, dependent, prerequisite, RelationType.REQUIRES_PREREQUISITE);
        when(edges.findPrerequisiteEdgesWithin(java.util.List.of(
                root.id(), dependent.id(), prerequisite.id()))).thenReturn(List.of(requires));

        List<KnowledgeGraphService.PrerequisiteRelation> relations =
                service.prerequisiteRelations(root.id());

        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).prerequisiteId()).isEqualTo(prerequisite.id());
        assertThat(relations.get(0).dependentNodeId()).isEqualTo(dependent.id());
    }
}

package com.syllabai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.TestIds;
import com.syllabai.knowledge.dto.NodeView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * V15 tree-fold extension: misconceptions fold under CONCEPT nodes through
 * misconception-family edges (REMEDIATED_BY / WRONG_ANSWER_PATTERN /
 * MISCONCEPTION_OF) — the settled T-C11 store attaches 13 of its 15
 * misconceptions without any MISCONCEPTION_OF edge. The V6 contract (TOPIC/
 * SUBTOPIC + strict MISCONCEPTION_OF) must stay exactly as it was, and the
 * plain tree must never fold.
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeGraphServiceFoldTest {

    private final KnowledgeNodeRepository nodes = mock(KnowledgeNodeRepository.class);
    private final KnowledgeEdgeRepository edges = mock(KnowledgeEdgeRepository.class);
    private final KnowledgeGraphRepository graph = mock(KnowledgeGraphRepository.class);

    private final KnowledgeGraphService service =
            new KnowledgeGraphService(nodes, edges, graph);

    @Test
    @DisplayName("a concept folds its remediation-linked misconception into the tree")
    void conceptFoldsRemediationLinkedMisconception() {
        UUID rootId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID conceptId = UUID.randomUUID();
        UUID misconceptionId = UUID.randomUUID();

        KnowledgeNode root = node(rootId, "4CH1", NodeType.SUBJECT);
        KnowledgeNode section = node(sectionId, "4CH1-S3", NodeType.UNIT);
        KnowledgeNode concept = node(conceptId, "4CH1-CON-BOND-ENERGY-CALC", NodeType.CONCEPT);
        KnowledgeNode misconception = node(misconceptionId, "4CH1-MIS-BOND-ENERGY-COUNT",
                NodeType.MISCONCEPTION);

        when(nodes.findById(rootId)).thenReturn(Optional.of(root));
        lenient().when(nodes.findById(sectionId)).thenReturn(Optional.of(section));
        lenient().when(nodes.findById(conceptId)).thenReturn(Optional.of(concept));
        lenient().when(nodes.findById(misconceptionId)).thenReturn(Optional.of(misconception));
        when(edges.findChildren(rootId)).thenReturn(List.of(edge(section, root)));
        when(edges.findChildren(sectionId)).thenReturn(List.of(edge(concept, section)));
        when(edges.findChildren(conceptId)).thenReturn(List.of());
        when(graph.findAssociatedMisconceptions(conceptId))
                .thenReturn(List.of(misconception));   // via REMEDIATED_BY in production

        NodeView tree = service.treeWithMisconceptions(rootId);

        NodeView conceptView = tree.children().get(0).children().get(0);
        assertThat(conceptView.code()).isEqualTo("4CH1-CON-BOND-ENERGY-CALC");
        assertThat(conceptView.type()).isEqualTo("CONCEPT");
        assertThat(conceptView.children()).hasSize(1);
        assertThat(conceptView.children().get(0).code()).isEqualTo("4CH1-MIS-BOND-ENERGY-COUNT");
        assertThat(conceptView.children().get(0).type()).isEqualTo("MISCONCEPTION");
    }

    @Test
    @DisplayName("V6 contract unchanged: a topic folds misconceptions via MISCONCEPTION_OF only")
    void v6TopicFoldPreserved() {
        UUID rootId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        UUID misId = UUID.randomUUID();

        KnowledgeNode root = node(rootId, "CHM", NodeType.SUBJECT);
        KnowledgeNode topic = node(topicId, "WCH11-T3.2", NodeType.SUBTOPIC);
        KnowledgeNode mis = node(misId, "MIS-T3.2-01", NodeType.MISCONCEPTION);

        when(nodes.findById(rootId)).thenReturn(Optional.of(root));
        lenient().when(nodes.findById(topicId)).thenReturn(Optional.of(topic));
        lenient().when(nodes.findById(misId)).thenReturn(Optional.of(mis));
        when(edges.findChildren(rootId)).thenReturn(List.of(edge(topic, root)));
        when(edges.findChildren(topicId)).thenReturn(List.of());
        when(graph.findMisconceptions(topicId)).thenReturn(List.of(mis));

        NodeView tree = service.treeWithMisconceptions(rootId);

        assertThat(tree.children().get(0).children()).hasSize(1);
        assertThat(tree.children().get(0).children().get(0).code()).isEqualTo("MIS-T3.2-01");
    }

    @Test
    @DisplayName("the plain tree never folds misconceptions")
    void plainTreeNeverFolds() {
        UUID rootId = UUID.randomUUID();
        UUID conceptId = UUID.randomUUID();
        KnowledgeNode root = node(rootId, "4CH1", NodeType.SUBJECT);
        KnowledgeNode concept = node(conceptId, "4CH1-CON-BOND-ENERGY-CALC", NodeType.CONCEPT);

        when(nodes.findById(rootId)).thenReturn(Optional.of(root));
        lenient().when(nodes.findById(conceptId)).thenReturn(Optional.of(concept));
        when(edges.findChildren(rootId)).thenReturn(List.of(edge(concept, root)));
        when(edges.findChildren(conceptId)).thenReturn(List.of());

        NodeView tree = service.tree(rootId);

        assertThat(tree.children().get(0).children()).isEmpty();
    }

    // ── fixtures ───────────────────────────────────────────────────

    private static KnowledgeNode node(UUID id, String code, NodeType type) {
        KnowledgeNode n = new KnowledgeNode(code, type, code + " title",
                null, KnowledgeNode.ValidationStatus.SUGGESTED, "test", "test");
        return TestIds.withId(n, id);
    }

    private static KnowledgeEdge edge(KnowledgeNode child, KnowledgeNode parent) {
        return new KnowledgeEdge(child, parent, RelationType.PART_OF, null, null,
                KnowledgeNode.ValidationStatus.VALIDATED, "test", "test");
    }
}

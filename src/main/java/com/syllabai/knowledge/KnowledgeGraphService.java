package com.syllabai.knowledge;

import com.syllabai.knowledge.dto.NodeView;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.shared.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles read models over the KG: nested topic trees, prerequisite chains,
 * misconception lists (Master Spec §6.3).
 *
 * <p>Tree depth is bounded by the syllabus hierarchy (subject→unit→topic→subtopic,
 * 4–5 levels), so recursive descent issues at most one children-query per node —
 * acceptable for Cycle-1 graph sizes; revisit with a batched CTE if the KG grows
 * past ~10k nodes.</p>
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeGraphService {

    private final KnowledgeNodeRepository nodes;
    private final KnowledgeEdgeRepository edges;
    private final KnowledgeGraphRepository graph;

    public KnowledgeGraphService(KnowledgeNodeRepository nodes,
                                 KnowledgeEdgeRepository edges,
                                 KnowledgeGraphRepository graph) {
        this.nodes = nodes;
        this.edges = edges;
        this.graph = graph;
    }

    /**
     * Nested PART_OF tree under a root node (typically a SUBJECT node linked from
     * the curriculum module).
     */
    public NodeView tree(UUID rootId) {
        return toView(node(rootId), false);
    }

    /**
     * Same tree with misconception nodes folded in as children of their topics.
     */
    public NodeView treeWithMisconceptions(UUID rootId) {
        return toView(node(rootId), true);
    }

    /**
     * Transitive prerequisite closure, deepest-first — the remediation path a
     * struggling student should walk backwards (Paper A type-1a prerequisite gaps).
     */
    public List<PrerequisiteView> prerequisiteChain(UUID nodeId) {
        return graph.findPrerequisiteClosure(nodeId).stream()
                .map(PrerequisiteView::from)
                .toList();
    }

    public List<NodeView> misconceptions(UUID topicNodeId) {
        return graph.findMisconceptions(topicNodeId).stream()
                .map(NodeView::flat)
                .toList();
    }

    public KnowledgeNode node(UUID id) {
        return nodes.findById(id)
                .orElseThrow(() -> new NotFoundException("knowledge node", id));
    }

    // ── internals ──────────────────────────────────────────────────

    private NodeView toView(KnowledgeNode n, boolean withMisconceptions) {
        List<NodeView> children = new ArrayList<>();
        for (KnowledgeEdge edge : edges.findChildren(n.id())) {
            children.add(toView(edge.source(), withMisconceptions));
        }
        if (withMisconceptions
                && (n.nodeType() == NodeType.TOPIC || n.nodeType() == NodeType.SUBTOPIC)) {
            for (KnowledgeNode m : graph.findMisconceptions(n.id())) {
                children.add(NodeView.flat(m));
            }
        }
        return new NodeView(n.id(), n.code(), n.nodeType().name(), n.title(), n.description(),
                n.validationStatus().name(), n.provenance(), List.copyOf(children));
    }
}

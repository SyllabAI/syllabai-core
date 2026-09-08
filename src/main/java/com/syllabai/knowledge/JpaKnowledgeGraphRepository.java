package com.syllabai.knowledge;

import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link KnowledgeGraphRepository} implementation over Postgres
 * (recursive CTEs in {@code KnowledgeNodeRepository}, Master Spec §24).
 */
@Service
@Transactional(readOnly = true)
public class JpaKnowledgeGraphRepository implements KnowledgeGraphRepository {

    private final KnowledgeNodeRepository nodes;
    private final KnowledgeEdgeRepository edges;

    public JpaKnowledgeGraphRepository(KnowledgeNodeRepository nodes, KnowledgeEdgeRepository edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    @Override
    public List<KnowledgeNode> findPrerequisites(UUID nodeId) {
        requireNode(nodeId);
        return edges.findDirectPrerequisites(nodeId).stream()
                .map(KnowledgeEdge::source)
                .toList();
    }

    @Override
    public List<PrerequisiteWithDepth> findPrerequisiteClosure(UUID nodeId) {
        requireNode(nodeId);
        List<Object[]> rows = nodes.findPrerequisiteClosure(nodeId);
        List<UUID> ids = rows.stream().map(r -> (UUID) r[0]).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        var byId = nodes.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeNode::id, n -> n));
        return rows.stream()
                .map(r -> new PrerequisiteWithDepth(byId.get(r[0]), ((Number) r[1]).intValue()))
                .toList();
    }

    @Override
    public List<KnowledgeNode> findSubtree(UUID nodeId) {
        requireNode(nodeId);
        List<UUID> ids = nodes.findSubtreeIds(nodeId);
        return nodes.findAllById(ids).stream()
                .sorted(java.util.Comparator.comparing(KnowledgeNode::code))
                .toList();
    }

    @Override
    public List<KnowledgeNode> findMisconceptions(UUID topicNodeId) {
        requireNode(topicNodeId);
        // MISCONCEPTION_OF runs misconception → topic (source = the misconception,
        // §7 seed contract): select edges pointing AT the topic and return their
        // sources. The pre-fix query read edges FROM the topic and mapped targets —
        // both directions inverted, so misconceptions never appeared on any read
        // surface (tree, mastery map, NBA MISCONCEPTION_SUSPECTED) despite the
        // BDT learner state being correct. Found in live browser verification.
        return edges.findMisconceptionEdgesTo(topicNodeId).stream()
                .map(KnowledgeEdge::source)
                .toList();
    }

    private void requireNode(UUID nodeId) {
        if (!nodes.existsById(nodeId)) {
            throw new NotFoundException("knowledge node", nodeId);
        }
    }
}

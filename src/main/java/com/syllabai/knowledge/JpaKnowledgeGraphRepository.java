package com.syllabai.knowledge;

import com.syllabai.shared.NotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                .map(KnowledgeEdge::target)
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
    public Map<UUID, List<PrerequisiteWithDepth>> findPrerequisiteClosures(Collection<UUID> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = nodes.findPrerequisiteClosureRows(nodeIds);
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<UUID> prereqIds = rows.stream().map(r -> (UUID) r[1]).distinct().toList();
        var byId = nodes.findAllById(prereqIds).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeNode::id, n -> n));
        // rows arrive ordered (origin, depth DESC, node) — per-origin slices
        // keep the single-node contract by construction (M3 tranche 2)
        Map<UUID, List<PrerequisiteWithDepth>> result = new LinkedHashMap<>();
        for (Object[] r : rows) {
            result.computeIfAbsent((UUID) r[0], k -> new ArrayList<>())
                    .add(new PrerequisiteWithDepth(byId.get(r[1]), ((Number) r[2]).intValue()));
        }
        return result;
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

    @Override
    public Map<UUID, List<KnowledgeNode>> findMisconceptionsForTopics(Collection<UUID> topicNodeIds) {
        if (topicNodeIds == null || topicNodeIds.isEmpty()) {
            return Map.of();
        }
        // same edge direction contract as findMisconceptions above: the
        // misconception is the edge SOURCE, the topic the TARGET
        Map<UUID, List<KnowledgeNode>> result = new LinkedHashMap<>();
        for (KnowledgeEdge e : edges.findMisconceptionEdgesToTopics(topicNodeIds)) {
            result.computeIfAbsent(e.target().id(), k -> new ArrayList<>()).add(e.source());
        }
        return result;
    }

    @Override
    public List<KnowledgeNode> findAssociatedMisconceptions(UUID nodeId) {
        requireNode(nodeId);
        // V15 concept-graph fold: misconceptions attach not only via
        // MISCONCEPTION_OF ("about") but also via REMEDIATED_BY / WRONG_ANSWER_
        // PATTERN edges into this node — the settled T-C11 store connects 13 of
        // its 15 misconceptions that way. Read-model widening only: the edges
        // already exist; nothing new is invented here.
        return edges.findMisconceptionFamilyEdgesTo(nodeId).stream()
                .map(KnowledgeEdge::source)
                .distinct()
                .sorted(java.util.Comparator.comparing(KnowledgeNode::code))
                .toList();
    }

    private void requireNode(UUID nodeId) {
        if (!nodes.existsById(nodeId)) {
            throw new NotFoundException("knowledge node", nodeId);
        }
    }
}

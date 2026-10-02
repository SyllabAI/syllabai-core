package com.syllabai.knowledge;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port for knowledge-graph traversal (Master Spec §23 example contract).
 *
 * <p>The Postgres implementation is {@link KnowledgeGraphService} over
 * {@code KnowledgeNodeRepository}/{@code KnowledgeEdgeRepository} with recursive CTEs;
 * the abstraction keeps the graph provider substitutable (Master Spec §2.3 —
 * Neo4j/HelixDB could replace the storage later without touching domain services).</p>
 */
public interface KnowledgeGraphRepository {

    /** Direct prerequisites of a node (what the student needs first). */
    List<KnowledgeNode> findPrerequisites(UUID nodeId);

    /** Transitive prerequisite closure with hop depth (deepest first). */
    List<PrerequisiteWithDepth> findPrerequisiteClosure(UUID nodeId);

    /**
     * Batched transitive prerequisite closures for several origin nodes (M3
     * tranche 2), keyed by origin id (missing key = no prerequisites).
     * Within-origin order matches {@link #findPrerequisiteClosure(UUID)}
     * (deepest first, then node id). Caller supplies structure-surface node
     * ids — no per-origin existence check.
     */
    Map<UUID, List<PrerequisiteWithDepth>> findPrerequisiteClosures(Collection<UUID> nodeIds);

    /** The full subtree under a node (inclusive), via PART_OF edges. */
    List<KnowledgeNode> findSubtree(UUID nodeId);

    /** Misconception nodes attached to a topic node. */
    List<KnowledgeNode> findMisconceptions(UUID topicNodeId);

    /**
     * Batched misconception attachments for several topic nodes (M3 tranche
     * 2), keyed by topic id (missing key = none attached) — the IN form of
     * {@link #findMisconceptions(UUID)}.
     */
    Map<UUID, List<KnowledgeNode>> findMisconceptionsForTopics(Collection<UUID> topicNodeIds);

    /**
     * Misconception nodes associated with a node through any misconception-family
     * edge pointing at it (MISCONCEPTION_OF / REMEDIATED_BY / WRONG_ANSWER_PATTERN)
     * — the V15 concept-graph tree fold. Distinct + code-ordered.
     */
    List<KnowledgeNode> findAssociatedMisconceptions(UUID nodeId);

    record PrerequisiteWithDepth(KnowledgeNode node, int depth) {
    }
}

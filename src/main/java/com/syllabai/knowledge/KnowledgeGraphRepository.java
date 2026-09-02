package com.syllabai.knowledge;

import java.util.List;
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

    /** The full subtree under a node (inclusive), via PART_OF edges. */
    List<KnowledgeNode> findSubtree(UUID nodeId);

    /** Misconception nodes attached to a topic node. */
    List<KnowledgeNode> findMisconceptions(UUID topicNodeId);

    record PrerequisiteWithDepth(KnowledgeNode node, int depth) {
    }
}

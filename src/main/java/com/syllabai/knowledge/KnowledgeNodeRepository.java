package com.syllabai.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeNodeRepository extends JpaRepository<KnowledgeNode, UUID> {

    Optional<KnowledgeNode> findByCode(String code);

    List<KnowledgeNode> findByNodeTypeOrderByCode(NodeType nodeType);

    @Query(value = """
            WITH RECURSIVE subtree AS (
                SELECT n.id
                FROM knowledge_nodes n
                WHERE n.id = :rootId
                UNION
                SELECT e.source_node_id
                FROM knowledge_edges e
                JOIN subtree s ON e.target_node_id = s.id
                WHERE e.relation_type = 'PART_OF'
            )
            SELECT n.id FROM knowledge_nodes n WHERE n.id IN (SELECT id FROM subtree)
            """, nativeQuery = true)
    List<UUID> findSubtreeIds(@Param("rootId") UUID rootId);

    /**
     * Direct prerequisite ids of a node (first hop, REQUIRES_PREREQUISITE into this node).
     */
    @Query(value = """
            SELECT e.source_node_id
            FROM knowledge_edges e
            WHERE e.target_node_id = :nodeId AND e.relation_type = 'REQUIRES_PREREQUISITE'
            """, nativeQuery = true)
    List<UUID> findDirectPrerequisiteIds(@Param("nodeId") UUID nodeId);

    /**
     * Transitive prerequisite closure of a node, with BFS depth (1 = direct).
     * Ordered deepest-first so callers can present remediation paths.
     */
    @Query(value = """
            WITH RECURSIVE prereq AS (
                SELECT e.source_node_id AS node_id, 1 AS depth
                FROM knowledge_edges e
                WHERE e.target_node_id = :nodeId AND e.relation_type = 'REQUIRES_PREREQUISITE'
                UNION
                SELECT e.source_node_id, p.depth + 1
                FROM knowledge_edges e
                JOIN prereq p ON e.target_node_id = p.node_id
                WHERE e.relation_type = 'REQUIRES_PREREQUISITE' AND p.depth < 10
            )
            SELECT node_id, MAX(depth) AS depth
            FROM prereq
            GROUP BY node_id
            ORDER BY depth DESC, node_id
            """, nativeQuery = true)
    List<Object[]> findPrerequisiteClosure(@Param("nodeId") UUID nodeId);

    /**
     * Misconception nodes attached to a topic (via MISCONCEPTION_OF edges into the topic).
     */
    @Query(value = """
            SELECT e.source_node_id
            FROM knowledge_edges e
            WHERE e.target_node_id = :topicId AND e.relation_type = 'MISCONCEPTION_OF'
            """, nativeQuery = true)
    List<UUID> findMisconceptionIds(@Param("topicId") UUID topicId);
}

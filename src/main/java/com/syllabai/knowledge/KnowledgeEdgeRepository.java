package com.syllabai.knowledge;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KnowledgeEdgeRepository extends JpaRepository<KnowledgeEdge, UUID> {

    /**
     * Children of a node (PART_OF edges pointing into the parent), ordered by code.
     */
    @Query("""
            select e from KnowledgeEdge e
            join fetch e.source
            where e.target.id = :parentId and e.relationType = com.syllabai.knowledge.RelationType.PART_OF
            order by e.source.code
            """)
    List<KnowledgeEdge> findChildren(@Param("parentId") UUID parentId);

    @Query("""
            select e from KnowledgeEdge e
            join fetch e.source
            where e.target.id = :nodeId
              and e.relationType = com.syllabai.knowledge.RelationType.REQUIRES_PREREQUISITE
            """)
    List<KnowledgeEdge> findDirectPrerequisites(@Param("nodeId") UUID nodeId);

    @Query("""
            select e from KnowledgeEdge e
            join fetch e.target
            where e.source.id = :nodeId
              and e.relationType = com.syllabai.knowledge.RelationType.MISCONCEPTION_OF
            """)
    List<KnowledgeEdge> findMisconceptionEdgesFrom(@Param("nodeId") UUID nodeId);
}

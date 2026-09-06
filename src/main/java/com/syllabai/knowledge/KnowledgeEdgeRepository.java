package com.syllabai.knowledge;

import java.util.List;
import java.util.Optional;
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

    /**
     * REQUIRES_PREREQUISITE edges with BOTH endpoints inside the given node set —
     * the drawable prerequisite relations for the personalized mastery-map read
     * model (F-034). Source = prerequisite, target = the node that requires it.
     */
    @Query("""
            select e from KnowledgeEdge e
            join fetch e.source
            join fetch e.target
            where e.relationType = com.syllabai.knowledge.RelationType.REQUIRES_PREREQUISITE
              and e.source.id in :nodeIds
              and e.target.id in :nodeIds
            """)
    List<KnowledgeEdge> findPrerequisiteEdgesWithin(@Param("nodeIds") java.util.Collection<UUID> nodeIds);

    @Query("""
            select e from KnowledgeEdge e
            join fetch e.target
            where e.source.id = :nodeId
              and e.relationType = com.syllabai.knowledge.RelationType.MISCONCEPTION_OF
            """)
    List<KnowledgeEdge> findMisconceptionEdgesFrom(@Param("nodeId") UUID nodeId);

    /** The PART_OF edge that hangs a node under its parent (§7 review workflow). */
    Optional<KnowledgeEdge> findBySourceIdAndRelationType(
            @Param("sourceId") UUID sourceId, @Param("relationType") RelationType relationType);

    /** All PART_OF edges inside a subtree — bulk validation (version gate). */
    @Query("""
            select e from KnowledgeEdge e
            where e.relationType = com.syllabai.knowledge.RelationType.PART_OF
              and e.source.id in :nodeIds
            """)
    List<KnowledgeEdge> findPartOfEdgesFrom(@Param("nodeIds") java.util.Collection<UUID> nodeIds);
}

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
            join fetch e.source
            where e.target.id = :nodeId
              and e.relationType = com.syllabai.knowledge.RelationType.MISCONCEPTION_OF
            """)
    List<KnowledgeEdge> findMisconceptionEdgesTo(@Param("nodeId") UUID nodeId);

    /** The PART_OF edge that hangs a node under its parent (§7 review workflow). */
    Optional<KnowledgeEdge> findBySourceIdAndRelationType(
            @Param("sourceId") UUID sourceId, @Param("relationType") RelationType relationType);

    /** One specific edge identity — idempotent re-seeding of the (V15) concept graph. */
    Optional<KnowledgeEdge> findBySourceIdAndTargetIdAndRelationType(
            @Param("sourceId") UUID sourceId, @Param("targetId") UUID targetId,
            @Param("relationType") RelationType relationType);

    /**
     * Misconception-family edges pointing at a node — the T-C11 concept-graph
     * attach points (V15). A misconception joins the read tree through any of
     * MISCONCEPTION_OF (about), REMEDIATED_BY (its corrective concept) or
     * WRONG_ANSWER_PATTERN (where it shows up). No new edge kinds: this only
     * widens which <em>existing</em> edges the tree fold considers.
     */
    @Query("""
            select e from KnowledgeEdge e
            join fetch e.source
            where e.target.id = :nodeId
              and e.relationType in (com.syllabai.knowledge.RelationType.MISCONCEPTION_OF,
                                     com.syllabai.knowledge.RelationType.REMEDIATED_BY,
                                     com.syllabai.knowledge.RelationType.WRONG_ANSWER_PATTERN)
            """)
    List<KnowledgeEdge> findMisconceptionFamilyEdgesTo(@Param("nodeId") UUID nodeId);

    /**
     * Misconception-family edges whose TARGET is inside the given node set —
     * the bulk form of {@link #findMisconceptionFamilyEdgesTo(UUID)}. The teacher
     * concept-graph edge read model (V15) uses it to widen the endpoint scope:
     * misconceptions attach to the PART_OF subtree through these edges (they are
     * edge sources, not PART_OF members), so the remediation / wrong-answer-pattern
     * edges are visible alongside the prerequisite ones (pilot-readiness
     * session-56 fix — the read model previously dropped all 29 misconception-family
     * edges of the settled store).
     */
    @Query("""
            select e from KnowledgeEdge e
            join fetch e.source
            where e.target.id in :nodeIds
              and e.relationType in (com.syllabai.knowledge.RelationType.MISCONCEPTION_OF,
                                     com.syllabai.knowledge.RelationType.REMEDIATED_BY,
                                     com.syllabai.knowledge.RelationType.WRONG_ANSWER_PATTERN)
            """)
    List<KnowledgeEdge> findMisconceptionFamilyEdgesWithin(
            @Param("nodeIds") java.util.Collection<UUID> nodeIds);

    /**
     * Every non-PART_OF edge with BOTH endpoints inside the given node set —
     * the teacher-facing concept-graph read model (V15). PART_OF is excluded:
     * curriculum structure is the tree's job, this carries semantic relations.
     */
    @Query("""
            select e from KnowledgeEdge e
            join fetch e.source
            join fetch e.target
            where e.relationType <> com.syllabai.knowledge.RelationType.PART_OF
              and e.source.id in :nodeIds
              and e.target.id in :nodeIds
            """)
    List<KnowledgeEdge> findSemanticEdgesWithin(@Param("nodeIds") java.util.Collection<UUID> nodeIds);

    /** All PART_OF edges inside a subtree — bulk validation (version gate). */
    @Query("""
            select e from KnowledgeEdge e
            where e.relationType = com.syllabai.knowledge.RelationType.PART_OF
              and e.source.id in :nodeIds
            """)
    List<KnowledgeEdge> findPartOfEdgesFrom(@Param("nodeIds") java.util.Collection<UUID> nodeIds);
}

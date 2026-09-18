package com.syllabai.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionRepository extends JpaRepository<Question, UUID> {

    @EntityGraph(attributePaths = "options")
    @Query("select q from Question q where q.id = :id")
    Optional<Question> findWithOptions(@Param("id") UUID id);

    @EntityGraph(attributePaths = "options")
    @Query("""
            select q from Question q
            where q.active = true
              and (q.primaryTopicNodeId = :nodeId or exists (
                    select 1 from QuestionTopic qt where qt.question = q and qt.nodeId = :nodeId))
            order by q.difficulty
            """)
    List<Question> findActiveByTopic(@Param("nodeId") UUID nodeId);

    @EntityGraph(attributePaths = "options")
    @Query("""
            select q from Question q
            where q.active = true
              and (q.primaryTopicNodeId in :nodeIds or exists (
                    select 1 from QuestionTopic qt where qt.question = q and qt.nodeId in :nodeIds))
            order by q.difficulty
            """)
    List<Question> findActiveWithin(@Param("nodeIds") java.util.Collection<UUID> nodeIds);

    @EntityGraph(attributePaths = "options")
    @Query("select q from Question q where q.active = true order by q.difficulty")
    List<Question> findAllActive();

    @EntityGraph(attributePaths = "options")
    List<Question> findAllByOrderByDifficultyAsc();

    /** questions of one exam paper, difficulty-ordered (paper detail view) */
    @EntityGraph(attributePaths = "options")
    List<Question> findAllByExamPaperIdOrderByDifficultyAsc(UUID examPaperId);

    /**
     * Review-queue v3 (sprint 2 §7): per-paper question census and how many
     * questions are mapped to REAL curriculum topics. A mapped question has a
     * question_topics row (§10 mapping writes primary + secondaries); ingested
     * questions carry only their ING-anchor in primary_topic_node_id, which is
     * outside every subject subtree by design. One batched aggregate query.
     * Rows: [paperId(UUID), totalQuestions(long), mappedQuestions(long)].
     */
    @org.springframework.data.jpa.repository.Query("""
            select q.examPaperId, count(q),
                   sum(case when exists (
                        select 1 from QuestionTopic qt where qt.question = q) then 1 else 0 end)
            from Question q
            where q.examPaperId is not null
            group by q.examPaperId
            """)
    java.util.List<Object[]> countAndMappedByPaper();

    /**
     * Review-queue v3 (sprint 2 §7): the topic coverage signal — distinct
     * primary topics of questions belonging to the given (VALIDATED) papers:
     * the topics the pilot can already practice. A candidate paper whose
     * mapped topics fall outside this set brings NOVEL curriculum coverage.
     * Read-only; caller supplies the validated-paper ids.
     */
    @org.springframework.data.jpa.repository.Query("""
            select distinct q.primaryTopicNodeId
            from Question q
            where q.examPaperId in :paperIds
              and q.primaryTopicNodeId is not null
            """)
    java.util.List<UUID> findDistinctPrimaryTopicsByPaperIds(
            @org.springframework.data.repository.query.Param("paperIds")
            java.util.Collection<UUID> paperIds);

    /**
     * ADR-026 SME bank replacement: deactivate every currently-active question
     * in one bulk update (rows survive — attempts, marking queues, evidence and
     * FK chains stay intact; only serving stops).
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "update Question q set q.active = false where q.active = true")
    int deactivateAllActive();
}

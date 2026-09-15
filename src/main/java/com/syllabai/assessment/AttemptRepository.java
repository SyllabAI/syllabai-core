package com.syllabai.assessment;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    @EntityGraph(attributePaths = "question")
    List<Attempt> findByLearnerIdOrderByCreatedAtDesc(UUID learnerId, Pageable pageable);

    /**
     * CLA §7.3/§7.4 post-attempt gate input: whether the requesting learner has
     * attempt evidence on this question (the same evidence substrate as Review
     * Hub). Deterministic read over resolved ids — never model-judged.
     */
    boolean existsByLearnerIdAndQuestionId(UUID learnerId, UUID questionId);

    /**
     * Sprint-2 §8 (Smart Lesson repeated-exposure avoidance): the question ids
     * inside a topic's servable set the learner has ALREADY attempted. One
     * batched query per recommendation — the caller passes the candidate
     * question ids of the single target topic.
     */
    @Query("""
            select distinct a.question.id from Attempt a
            where a.learner.id = :learnerId and a.question.id in :questionIds
            """)
    List<UUID> findAttemptedQuestionIds(@Param("learnerId") UUID learnerId,
                                        @Param("questionIds") Collection<UUID> questionIds);

    long countByLearnerId(UUID learnerId);

    /**
     * Graded-attempt correctness aggregates per condition for one learner+node
     * (Paper B §16 fluency gap). Rows: [timed(boolean), total(bigint), correct(bigint)].
     * Counts primary-topic and question_topics mappings; only attempts whose
     * evidence has fired (graded) are included.
     */
    @Query(value = """
            SELECT a.timed_condition AS timed, COUNT(*) AS total,
                   SUM(CASE WHEN a.correct THEN 1 ELSE 0 END) AS correct
            FROM attempts a
            JOIN questions q ON q.id = a.question_id
            WHERE a.learner_id = :learnerId
              AND a.evidence_emitted = TRUE
              AND (q.primary_topic_node_id = :nodeId OR EXISTS (
                    SELECT 1 FROM question_topics qt
                    WHERE qt.question_id = q.id AND qt.node_id = :nodeId))
            GROUP BY a.timed_condition
            """, nativeQuery = true)
    List<Object[]> aggregateGradedCorrectnessByCondition(@Param("learnerId") UUID learnerId,
                                                         @Param("nodeId") UUID nodeId);

    /**
     * Class-analytics aggregate (teacher class intelligence §2): per-learner
     * attempt counts, correctness and last-activity timestamp inside a
     * subject scope — questions mapped (primary or question_topics) into the
     * given node set, attempts within the recency window. One batched query
     * for the whole class; rows: [learnerId(UUID), total(long),
     * correct(long), lastActivity(Instant)].
     */
    @Query(value = """
            SELECT a.learner_id, COUNT(*),
                   SUM(CASE WHEN a.correct THEN 1 ELSE 0 END), MAX(a.created_at)
            FROM attempts a
            JOIN questions q ON q.id = a.question_id
            WHERE a.created_at >= :since
              AND (q.primary_topic_node_id IN (:nodeIds) OR EXISTS (
                    SELECT 1 FROM question_topics qt
                    WHERE qt.question_id = q.id AND qt.node_id IN (:nodeIds)))
            GROUP BY a.learner_id
            """, nativeQuery = true)
    List<Object[]> aggregateByLearnerSinceWithin(@Param("since") java.time.Instant since,
                                                 @Param("nodeIds") Collection<UUID> nodeIds);

    /**
     * Class-analytics drill-down (§5): the most recent attempts on questions
     * mapped to one topic node — representative learner evidence for the
     * teacher. Batched fetch plan (question included), page-limited by the
     * caller.
     */
    @EntityGraph(attributePaths = "question")
    @Query("""
            select a from Attempt a
            where a.question.primaryTopicNodeId = :nodeId or exists (
                select 1 from QuestionTopic qt
                where qt.question = a.question and qt.nodeId = :nodeId)
            order by a.createdAt desc
            """)
    List<Attempt> findRecentByTopicNode(@Param("nodeId") UUID nodeId, Pageable pageable);
}

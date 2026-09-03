package com.syllabai.assessment;

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
}

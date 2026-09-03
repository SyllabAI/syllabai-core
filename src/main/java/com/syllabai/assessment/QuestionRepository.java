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
    @Query("select q from Question q where q.active = true order by q.difficulty")
    List<Question> findAllActive();

    @EntityGraph(attributePaths = "options")
    List<Question> findAllByOrderByDifficultyAsc();
}

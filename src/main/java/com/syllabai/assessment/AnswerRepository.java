package com.syllabai.assessment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnswerRepository extends JpaRepository<Answer, UUID> {

    @EntityGraph(attributePaths = {"questionPart", "attempt", "attempt.question"})
    @Query("select a from Answer a where a.id = :id")
    Optional<Answer> findWithPartAndAttempt(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"questionPart", "attempt", "attempt.question"})
    List<Answer> findByAttemptIdOrderByQuestionPartId(UUID attemptId);

    @EntityGraph(attributePaths = {"questionPart", "attempt", "attempt.question"})
    @Query("""
            select a from Answer a
            where a.markingState = :state
            order by a.createdAt asc
            """)
    List<Answer> findByMarkingState(@Param("state") Answer.MarkingState state);

    @EntityGraph(attributePaths = {"questionPart", "attempt", "attempt.question"})
    @Query("""
            select a from Answer a
            where a.markingState in :states
            order by a.createdAt asc
            """)
    List<Answer> findByMarkingStates(@Param("states") Collection<Answer.MarkingState> states);

    @EntityGraph(attributePaths = {"questionPart", "attempt", "attempt.question"})
    @Query("""
            select a from Answer a
            where a.attempt.learnerId = :learnerId
            order by a.createdAt desc
            """)
    List<Answer> findByLearnerIdOrderByCreatedAtDesc(@Param("learnerId") UUID learnerId);

    /**
     * Class-analytics aggregate (teacher class intelligence §2): answers in a
     * marking state whose attempt's question is mapped (primary or
     * question_topics) into the given subject scope — one batched count for
     * the class overview's marking-work signal.
     */
    @Query("""
            select count(a) from Answer a
            where a.markingState = :state and (
                a.attempt.question.primaryTopicNodeId in :nodeIds or exists (
                    select 1 from QuestionTopic qt
                    where qt.question = a.attempt.question and qt.nodeId in :nodeIds))
            """)
    long countByMarkingStateWithin(@Param("state") Answer.MarkingState state,
                                   @Param("nodeIds") Collection<UUID> nodeIds);
}

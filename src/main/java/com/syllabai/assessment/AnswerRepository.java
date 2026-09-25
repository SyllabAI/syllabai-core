package com.syllabai.assessment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnswerRepository extends JpaRepository<Answer, UUID> {

    /**
     * Id-only resolution for the marking-transaction lock order (evidence-state
     * concurrency fix): resolves the owning attempt id WITHOUT loading entities,
     * so the caller can take the attempt row lock (findByIdForUpdate) before any
     * answer/attempt state enters its persistence context. FK-column path — no
     * LAZY proxy initialization (the established QuestionVersion.questionIdColumn
     * pattern).
     */
    @Query("select a.attempt.id from Answer a where a.id = :id")
    Optional<UUID> findAttemptIdById(@Param("id") UUID id);

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

    /**
     * Opt-in paged marking queue (G-5): same read model as
     * {@link #findByMarkingState(Answer.MarkingState)} with a TOTAL order —
     * createdAt asc, then id asc — so page boundaries are stable while rows
     * move through states. The unpaged list stays the compatibility surface
     * (the marking UI and the calibration harness read it); sort comes from
     * the caller's Pageable.
     */
    @EntityGraph(attributePaths = {"questionPart", "attempt", "attempt.question"})
    @Query("""
            select a from Answer a
            where a.markingState = :state
            """)
    Page<Answer> findPageByMarkingState(@Param("state") Answer.MarkingState state,
                                        Pageable pageable);

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

    /**
     * Marking throughput lane (sprint 2 §6): answer counts grouped by marking
     * state in ONE query — the workload overview. Rows: [state, count].
     * Read-only.
     */
    @Query("select a.markingState, count(a) from Answer a group by a.markingState")
    List<Object[]> countGroupedByMarkingState();
}

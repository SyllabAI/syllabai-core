package com.syllabai.smartmark;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SmartMarkResultRepository extends JpaRepository<SmartMarkResult, UUID> {

    /** latest accepted run per answer (κ pairing uses the newest decision) */
    @Query("""
            select r from SmartMarkResult r
            where r.answer.id = :answerId
            order by r.createdAt desc
            """)
    List<SmartMarkResult> findByAnswerIdOrderByCreatedAtDesc(@Param("answerId") UUID answerId);

    default Optional<SmartMarkResult> findLatest(UUID answerId) {
        var runs = findByAnswerIdOrderByCreatedAtDesc(answerId);
        return runs.isEmpty() ? Optional.empty() : Optional.of(runs.get(0));
    }

    /**
     * Marking throughput lane (sprint 2 §6): every smart-mark run for a batch
     * of answers in ONE query, oldest first — the caller keeps the newest run
     * per answer in memory. Bounded by the marking-queue page size, never the
     * full history of the table. The answer association is fetched eagerly:
     * open-in-view is OFF, so the caller assembles the queue with these runs
     * DETACHED and keyed by run.answerId() — an uninitialized lazy proxy would
     * throw LazyInitializationException there (every marked state 500ed while
     * PENDING worked, 2026-09-24).
     */
    @EntityGraph(attributePaths = {"answer"})
    @Query("""
            select r from SmartMarkResult r
            where r.answer.id in :answerIds
            order by r.createdAt asc
            """)
    List<SmartMarkResult> findByAnswerIdsOrderByCreatedAtAsc(
            @Param("answerIds") Collection<UUID> answerIds);

    /** all runs that passed validation — the κ sample population */
    @Query("select r from SmartMarkResult r where r.validationPassed = true order by r.createdAt asc")
    List<SmartMarkResult> findAllAccepted();

    @Query("""
            select r from SmartMarkResult r
            where r.validationPassed = true
              and r.answer.attempt.question.examPaperId = :paperId
            order by r.createdAt asc
            """)
    List<SmartMarkResult> findAcceptedByPaper(@Param("paperId") UUID paperId);
}

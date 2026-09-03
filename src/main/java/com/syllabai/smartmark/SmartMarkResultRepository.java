package com.syllabai.smartmark;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

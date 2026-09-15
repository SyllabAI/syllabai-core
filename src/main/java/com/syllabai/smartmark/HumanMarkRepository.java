package com.syllabai.smartmark;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HumanMarkRepository extends JpaRepository<HumanMark, UUID> {

    /** latest human decision per answer (κ pairing + override history) */
    @Query("""
            select h from HumanMark h
            where h.answer.id = :answerId
            order by h.createdAt desc
            """)
    List<HumanMark> findByAnswerIdOrderByCreatedAtDesc(@Param("answerId") UUID answerId);

    default java.util.Optional<HumanMark> findLatest(UUID answerId) {
        var marks = findByAnswerIdOrderByCreatedAtDesc(answerId);
        return marks.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(marks.get(0));
    }

    @Query("select h from HumanMark h order by h.createdAt asc")
    List<HumanMark> findAllByOrderByCreatedAtAsc();

    @Query("""
            select h from HumanMark h
            where h.answer.attempt.question.examPaperId = :paperId
            order by h.createdAt asc
            """)
    List<HumanMark> findByPaperOrderByCreatedAtAsc(@Param("paperId") UUID paperId);

    /**
     * Marking throughput lane (sprint 2 §6): every human mark for a batch of
     * answers in ONE query, oldest first — the caller keeps the newest per
     * answer in memory. Read-only.
     */
    @Query("""
            select h from HumanMark h
            where h.answer.id in :answerIds
            order by h.createdAt asc
            """)
    List<HumanMark> findByAnswerIdsOrderByCreatedAtAsc(
            @Param("answerIds") java.util.Collection<UUID> answerIds);

    /**
     * Marking throughput lane (sprint 2 §6): authoritative human marks recorded
     * since a point in time — the throughput windows (24h / 7d). One count per
     * call; read-only.
     */
    @Query("""
            select count(h) from HumanMark h
            where h.createdAt >= :since
            """)
    long countSince(@Param("since") java.time.Instant since);
}

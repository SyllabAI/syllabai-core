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
}

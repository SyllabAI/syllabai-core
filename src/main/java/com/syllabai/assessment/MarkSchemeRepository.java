package com.syllabai.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarkSchemeRepository extends JpaRepository<MarkScheme, UUID> {

    @EntityGraph(attributePaths = "points")
    @Query("select s from MarkScheme s where s.id = :id")
    Optional<MarkScheme> findWithPoints(@Param("id") UUID id);

    @EntityGraph(attributePaths = "points")
    Optional<MarkScheme> findFirstByQuestionVersionIdOrderByCreatedAtDesc(UUID questionVersionId);

    @EntityGraph(attributePaths = "points")
    @Query("""
            select s from MarkScheme s
            where s.validationState = com.syllabai.assessment.MarkScheme$ValidationState.SUGGESTED
            order by s.createdAt desc
            """)
    List<MarkScheme> findSuggested();

    /** T-C02 bridge reporting: the schemes of one imported paper's questions. */
    @Query("""
            select s from MarkScheme s
            where s.questionVersion.question.examPaperId = :paperId
            """)
    List<MarkScheme> findByPaperId(@Param("paperId") UUID paperId);

    /** V20 review-queue enrichment: per-paper scheme-state census (one aggregate query) */
    @Query("""
            select q.examPaperId, s.validationState, count(s)
            from MarkScheme s join s.questionVersion v join v.question q
            group by q.examPaperId, s.validationState
            """)
    List<Object[]> countByPaperAndState();
}

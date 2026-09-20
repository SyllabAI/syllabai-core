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

    /**
     * points AND points.questionPart are fetched eagerly here: the CLA part-level
     * pipeline reads mark points OUTSIDE any transaction (non-transactional
     * service, OSIV off) and selects them by part id — an accessor call on a
     * LAZY QuestionPart proxy would initialize it and blow up in production
     * (field-access entities: even id() initializes). The question-level path
     * is unaffected (it never touches part proxies); the extra join is cheap
     * and the scheme is question-granular.
     */
    @EntityGraph(attributePaths = {"points", "points.questionPart"})
    Optional<MarkScheme> findFirstByQuestionVersionIdOrderByCreatedAtDesc(UUID questionVersionId);

    /**
     * The marking-path selection (V25, gap G-2): only a VALIDATED scheme may back
     * Smart Mark — SUGGESTED, REJECTED and FLAGGED schemes never back marking
     * (the FLAGGED rule was V20's stated intent; this finder enforces it).
     * Review surfaces (content review, test builder, CLA) keep the unfiltered
     * finder above — they must see the newest scheme whatever its state.
     */
    @EntityGraph(attributePaths = "points")
    Optional<MarkScheme> findFirstByQuestionVersionIdAndValidationStateOrderByCreatedAtDesc(
            UUID questionVersionId, MarkScheme.ValidationState validationState);

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

    /**
     * Review-queue v3 (sprint 2 §7): mark-scheme linkage completeness —
     * per-paper count of DISTINCT questions that have at least one scheme on
     * any of their versions. Rows: [paperId(UUID), questionsWithScheme(long)].
     * A paper whose every question has a scheme is faster to review correctly
     * (the reviewer can compare answer key against source). Read-only.
     */
    @Query("""
            select q.examPaperId, count(distinct q.id)
            from MarkScheme s join s.questionVersion v join v.question q
            where q.examPaperId is not null
            group by q.examPaperId
            """)
    List<Object[]> countQuestionsWithSchemesByPaper();
}

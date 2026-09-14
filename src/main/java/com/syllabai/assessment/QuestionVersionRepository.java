package com.syllabai.assessment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionVersionRepository extends JpaRepository<QuestionVersion, UUID> {

    @EntityGraph(attributePaths = "parts")
    @Query("select v from QuestionVersion v where v.id = :id")
    Optional<QuestionVersion> findWithParts(@Param("id") UUID id);

    /** latest version of a question (highest version number), parts fetched */
    @EntityGraph(attributePaths = "parts")
    @Query("""
            select v from QuestionVersion v
            where v.question.id = :questionId
            order by v.version desc
            """)
    List<QuestionVersion> findByQuestionIdOrderByVersionDesc(@Param("questionId") UUID questionId);

    /**
     * All versions (parts fetched) of many questions in ONE query — the batched
     * counterpart of {@link #findByQuestionIdOrderByVersionDesc}. List-shaped
     * serving paths (allActive / activeWithin) previously issued one versions
     * query per structured question: ~1,800 sequential round-trips on the
     * unscoped surface (~90s on the pooled Neon connection). Serving boundaries
     * are unchanged — the spec still decides; this only changes fetch strategy.
     */
    @EntityGraph(attributePaths = "parts")
    @Query("select v from QuestionVersion v where v.question.id in :questionIds")
    List<QuestionVersion> findWithPartsByQuestionIdsIn(@Param("questionIds") Collection<UUID> questionIds);

    @Query("""
            select v from QuestionVersion v
            where v.validationState = com.syllabai.assessment.QuestionVersion$ValidationState.SUGGESTED
            order by v.createdAt desc
            """)
    List<QuestionVersion> findSuggested();

    @Query("""
            select v from QuestionVersion v
            where v.question.examPaperId = :paperId
            order by v.question.externalRef nulls last, v.version desc
            """)
    List<QuestionVersion> findByPaperId(@Param("paperId") UUID paperId);

    /** V20 review-queue enrichment: per-paper version-state census (one aggregate query) */
    @Query("""
            select q.examPaperId, v.validationState, count(v)
            from QuestionVersion v join v.question q
            group by q.examPaperId, v.validationState
            """)
    List<Object[]> countByPaperAndState();

    /** V20 review-queue enrichment: per-paper mean extraction confidence */
    @Query("""
            select q.examPaperId, avg(v.extractionConfidence)
            from QuestionVersion v join v.question q
            where v.extractionConfidence is not null
            group by q.examPaperId
            """)
    List<Object[]> avgExtractionConfidenceByPaper();
}

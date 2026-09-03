package com.syllabai.assessment;

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
}

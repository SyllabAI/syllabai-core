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
}

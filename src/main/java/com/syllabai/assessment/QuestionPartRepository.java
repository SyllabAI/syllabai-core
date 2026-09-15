package com.syllabai.assessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuestionPartRepository extends JpaRepository<QuestionPart, UUID> {

    List<QuestionPart> findByQuestionVersionIdOrderByOrdering(UUID questionVersionId);

    Optional<QuestionPart> findByQuestionVersionIdAndLabel(UUID questionVersionId, String label);

    /**
     * Scalar projection: the owning question id of a part, resolved through the
     * canonical part → version → question FK in a SINGLE query — no entity
     * hydration, no lazy loading. This is the boundary-safe lookup for read
     * models that hold only the part id outside a transaction (the CLA
     * pipeline is deliberately non-transactional with OSIV off; an entity
     * traversal here would LazyInitializationException in production).
     */
    @Query("select p.questionVersion.questionId from QuestionPart p where p.id = :partId")
    Optional<UUID> findQuestionIdByPartId(@Param("partId") UUID partId);
}

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
     *
     * <p>Native SQL deliberately: the derived JPQL path cannot name the
     * question id (QuestionVersion exposes the {@code question} association,
     * not a persistent {@code questionId} property — a derived query naming it
     * fails named-query validation at BOOT and kills the deploy). The schema
     * FK chain is stable: question_parts.question_version_id →
     * question_versions.question_id.</p>
     */
    @Query(value = "select v.question_id from question_parts p "
            + "join question_versions v on v.id = p.question_version_id "
            + "where p.id = :partId", nativeQuery = true)
    Optional<UUID> findQuestionIdByPartId(@Param("partId") UUID partId);
}

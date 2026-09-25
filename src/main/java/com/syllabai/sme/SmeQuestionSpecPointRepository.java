package com.syllabai.sme;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SmeQuestionSpecPointRepository
        extends JpaRepository<QuestionSpecPoint, UUID> {

    List<QuestionSpecPoint> findByQuestionId(UUID questionId);

    /**
     * Batched curriculum-code lookup for the learner question view (ADR-026):
     * one query resolves every question's spec-point codes (knowledge-node
     * codes, with the mapping role) for a whole practice list — the client
     * joins questions to revision notes with this, no per-question round trips.
     *
     * <p>T-C24: also carries the node's official applicability object verbatim
     * (papers / double_award_shared / rule for 4CH1; {@code null} on unscoped
     * rows) so paper/unit/tier scoping needs no second query.</p>
     */
    @Query("""
            select qsp.question.id as questionId, kn.code as code, qsp.role as role,
                   kn.applicability as applicability
            from QuestionSpecPoint qsp
                join KnowledgeNode kn on kn.id = qsp.specPointNodeId
            where qsp.question.id in :questionIds
            """)
    List<CodeProjection> findCodesByQuestionIdsIn(@Param("questionIds") Collection<UUID> questionIds);

    /** minimal projection: question id + curriculum code + mapping role (+ applicability, T-C24) */
    interface CodeProjection {
        UUID getQuestionId();
        String getCode();
        String getRole();
        Map<String, Object> getApplicability();
    }
}

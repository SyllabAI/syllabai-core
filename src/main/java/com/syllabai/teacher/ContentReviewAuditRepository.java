package com.syllabai.teacher;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ContentReviewAuditRepository
        extends JpaRepository<ContentReviewAudit, Long> {

    /** audit history for one review target (paper / version / scheme / question) */
    List<ContentReviewAudit> findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
            String targetType, UUID targetId);

    /**
     * Full audit for a paper review screen in ONE query: the paper's own rows
     * plus the rows of its children, each id matched within its own target
     * type (deterministic — no cross-type id assumptions).
     */
    @Query("""
            select a from ContentReviewAudit a
            where (a.targetType = 'exam_paper' and a.targetId = :paperId)
               or (a.targetType = 'question_version' and a.targetId in :versionIds)
               or (a.targetType = 'mark_scheme' and a.targetId in :schemeIds)
               or (a.targetType = 'question' and a.targetId in :questionIds)
            order by a.occurredAt desc
            """)
    List<ContentReviewAudit> findPaperAudit(@Param("paperId") UUID paperId,
                                            @Param("versionIds") Collection<UUID> versionIds,
                                            @Param("schemeIds") Collection<UUID> schemeIds,
                                            @Param("questionIds") Collection<UUID> questionIds);

    long countByAction(String action);
}

package com.syllabai.teacher.ingestion;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GlmOcrBridgeRecordRepository extends JpaRepository<GlmOcrBridgeRecord, UUID> {

    /** rerun spine: the same canonical (qp, ms) pair resolves to its existing record */
    Optional<GlmOcrBridgeRecord> findByQpDocumentIdAndMsDocumentId(
            String qpDocumentId, String msDocumentId);

    /** review surface: the bridge evidence for one imported paper */
    Optional<GlmOcrBridgeRecord> findByPaperId(@Param("paperId") UUID paperId);

    /** content-ops overview: how many imported pairs still require reconciliation review */
    @Query("select count(r) from GlmOcrBridgeRecord r where r.reconciliationStatus = 'REVIEW_REQUIRED'")
    long countRequiringReview();
}

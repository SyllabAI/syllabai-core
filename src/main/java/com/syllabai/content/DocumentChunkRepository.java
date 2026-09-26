package com.syllabai.content;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    List<DocumentChunk> findByDocumentRowIdOrderByChunkIndexAsc(UUID documentRowId);

    long countByDocumentRowId(UUID documentRowId);

    @Query("""
            select c from DocumentChunk c
            where c.documentRowId = ?1 and c.embeddedAt is null
            order by c.chunkIndex asc
            """)
    List<DocumentChunk> findPendingByDocumentRowId(UUID documentRowId);

    /**
     * Paper-question resolver tier 2b: document rows whose chunks carry the
     * exact V33 write-time paper identity (series + year + paper code, e.g.
     * "4CH1/1C") — the ingested QP/MS documents of a bound paper-question ask.
     * Deterministic metadata matching: no vector calls, no content regex.
     * Returns [document_row_id, paper_code] pairs (a row appears once per
     * matching chunk; callers de-duplicate and apply the serving law).
     */
    @Query("""
            select c.documentRowId, c.paperCode from DocumentChunk c
            where c.series = ?1 and c.year = ?2 and c.paperCode in ?3
            """)
    List<Object[]> findRowIdsByPaperIdentity(String series, Integer year, List<String> paperCodes);
}

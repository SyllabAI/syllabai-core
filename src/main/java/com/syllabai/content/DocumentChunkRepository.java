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
}

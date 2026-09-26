package com.syllabai.content;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByChecksum(String checksum);

    Optional<Document> findByDocumentIdAndDocVersion(String documentId, int docVersion);

    /** latest content-store row for a document id (imports pin their source identity there) */
    Optional<Document> findTopByDocumentIdOrderByDocVersionDesc(String documentId);

    /** latest content-store row for a stored file name (per-session question cards anchor here) */
    Optional<Document> findTopByFileNameOrderByDocVersionDesc(String fileName);

    List<Document> findAllByOrderByCreatedAtDesc();

    @Query("""
            select d from Document d
            where d.kind = com.syllabai.content.Document$Kind.MARK_SCHEME
            order by d.createdAt desc
            """)
    List<Document> findMarkSchemes();
}

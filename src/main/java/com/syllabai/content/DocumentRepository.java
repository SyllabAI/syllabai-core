package com.syllabai.content;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * The citation-view gate (L5): does this document row sit on the SERVING
     * side of the corpus law — the question paper or mark scheme of a
     * {@code VALIDATED} exam paper (the T-C20 paper branch), or itself
     * {@code VALIDATED} (the knowledge-layer branch)? This is the scope-free
     * mirror of the validation gates inside
     * {@code ChunkVectorRepository.SCOPE_EXISTS_VALIDATED} /
     * {@code ChunkLexicalRepository.searchServingEligible}: the curriculum
     * dimension belongs to retrieval, never to the citation drill-in of a
     * document a citation already points at — but the validation dimension is
     * load-bearing here too, because a citation drill-in IS serving (a corpus
     * import nothing serves must stay unreadable on every learner surface).
     */
    @Query(value = """
            select count(*) > 0
            from documents d
            where d.id = :rowId
              and (exists (select 1 from exam_papers p
                           where p.validation_state = 'VALIDATED'
                             and (p.question_paper_document_id = d.document_id
                               or p.mark_scheme_document_id = d.document_id))
                   or d.validation_state = 'VALIDATED')
            """, nativeQuery = true)
    boolean existsCitable(@Param("rowId") UUID rowId);
}

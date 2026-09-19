package com.syllabai.content;

import com.syllabai.curriculum.SubjectRepository;
import com.syllabai.shared.ConflictException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists syllabai-parser canonical documents (T-011's draft bridge, T-013's store).
 * Pipeline: validate (core-side, never trust across a process boundary) → dedup by
 * source checksum (idempotent corpus loads) → persist the document → chunk
 * deterministically → persist chunks. Embedding is a separate, re-runnable operation
 * ({@link DocumentEmbeddingService}) so content lands even with zero API keys.
 *
 * <p>Storage note: the raw request JSON is handed to the entity and stored in a JSONB
 * column — content-preserving (the JSON writer may normalize formatting). The §8
 * provenance spine for the original FILE is {@code source.checksum}, not the JSON
 * serialization.</p>
 */
@Service
public class ContentIngestionService {

    private final CanonicalDocumentValidator validator;
    private final ChunkingService chunking;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final SubjectRepository subjects;

    public ContentIngestionService(CanonicalDocumentValidator validator, ChunkingService chunking,
                                   DocumentRepository documents, DocumentChunkRepository chunks,
                                   SubjectRepository subjects) {
        this.validator = validator;
        this.chunking = chunking;
        this.documents = documents;
        this.chunks = chunks;
        this.subjects = subjects;
    }

    /**
     * @param rawJson the request body as received — stored via JSONB, content-preserving;
     *                the source checksum (§8) is the provenance spine for the original file
     */
    @Transactional
    public IngestionResult ingest(CanonicalDocumentDto doc, String rawJson, Document.Kind kind,
                                  UUID ingestedBy) {
        validator.validate(doc);

        var existing = documents.findByChecksum(doc.source().checksum());
        if (existing.isPresent()) {
            Document d = existing.get();
            if (d.kind() != kind) {
                throw new ConflictException("checksum " + doc.source().checksum()
                        + " already ingested as kind " + d.kind() + " (requested " + kind + ")");
            }
            return new IngestionResult(d.id(), d.documentId(), true, d.chunkCount(),
                    d.elementCount(), d.pageCount(), d.chunkCount());
        }

        List<ChunkDraft> drafts = chunking.chunk(doc, kind);

        // Embedding-v2 identity mirror (V33): resolve the subject ONCE per document
        // and stamp every chunk. A subjectCode that does not resolve is a LOUD
        // rejection — an invisible corpus (subject_id NULL is never served) must
        // not be creatable by a typo. Documents without a retrieval block stay
        // tolerated (legacy shape; their chunks are never served until re-ingested
        // with identity — fail-closed, plan §5 "nothing without subject_id").
        ChunkMetadata meta = new ChunkMetadata(kind, resolveSubjectId(doc), series(doc),
                doc.retrieval() == null ? null : doc.retrieval().year(),
                doc.retrieval() == null ? null : trimOrNull(doc.retrieval().paperCode()),
                null, // per-chunk atom number is stamped below from the draft group key
                doc.retrieval() == null ? null : doc.retrieval().specCodes(),
                ChunkVectorRepository.CURRENT_EMBED_REV);

        Document document = new Document(
                doc.documentId(),
                doc.schemaVersion(),
                doc.version() == null ? 1 : doc.version(),
                kind,
                doc.source().uri(),
                doc.source().fileName(),
                doc.source().mimeType(),
                doc.source().checksum(),
                doc.source().checksumAlgorithm() == null || doc.source().checksumAlgorithm().isBlank()
                        ? "SHA-256" : doc.source().checksumAlgorithm(),
                doc.pageCount(),
                doc.totalElementCount(),
                doc.textElementCount(),
                drafts.size(),
                doc.provenance().engine(),
                doc.provenance().engineVersion(),
                parseInstant(doc.provenance().extractedAt()),
                rawJson,
                ingestedBy);
        Document saved = documents.save(document);

        for (int i = 0; i < drafts.size(); i++) {
            ChunkDraft draft = drafts.get(i);
            chunks.save(new DocumentChunk(saved.id(), i, draft.content(),
                    draft.pageStart(), draft.pageEnd(), draft.elementIds(),
                    draft.tokenEstimate(),
                    new ChunkMetadata(meta.kind(), meta.subjectId(), meta.series(), meta.year(),
                            meta.paperCode(), atomNumber(draft), meta.specCodes(),
                            meta.embedRev())));
        }
        return new IngestionResult(saved.id(), saved.documentId(), false, drafts.size(),
                saved.elementCount(), saved.pageCount(), drafts.size());
    }

    /** ISO-8601 from the parser provenance; unparsable values stay null (reported, not fatal). */
    private static Instant parseInstant(String extractedAt) {
        if (extractedAt == null || extractedAt.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(extractedAt).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Resolves {@code retrieval.subjectCode} to a subjects.id. A present but
     * unresolvable code is a hard failure (the corpus would be silently
     * unservable); an absent code resolves to null (legacy-tolerant path).
     */
    private UUID resolveSubjectId(CanonicalDocumentDto doc) {
        if (doc.retrieval() == null || doc.retrieval().subjectCode() == null
                || doc.retrieval().subjectCode().isBlank()) {
            return null;
        }
        String code = doc.retrieval().subjectCode().strip();
        return subjects.findByCode(code)
                .orElseThrow(() -> new com.syllabai.shared.NotFoundException(
                        "Subject (retrieval.subjectCode)", code)).id();
    }

    private static String series(CanonicalDocumentDto doc) {
        if (doc.retrieval() == null || doc.retrieval().series() == null
                || doc.retrieval().series().isBlank()) {
            return null;
        }
        return doc.retrieval().series().strip(); // validator already enum-checked
    }

    /** draft group key → the atom number column ("q3" → "3"); null when absent */
    private static String atomNumber(ChunkDraft draft) {
        String normalized = ChunkHeaderBuilder.normalizeAtom(draft.groupKey());
        if (normalized == null) {
            return null;
        }
        return normalized.length() > 10 ? normalized.substring(0, 10) : normalized;
    }

    private static String trimOrNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    public record IngestionResult(UUID id, String documentId, boolean duplicate, int chunks,
                                  int elements, int pages, int totalChunks) {
    }
}

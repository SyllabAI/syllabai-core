package com.syllabai.content;

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
 * source checksum (idempotent corpus loads) → persist the document verbatim → chunk
 * deterministically → persist chunks. Embedding is a separate, re-runnable operation
 * ({@link DocumentEmbeddingService}) so content lands even with zero API keys.
 */
@Service
public class ContentIngestionService {

    private final CanonicalDocumentValidator validator;
    private final ChunkingService chunking;
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public ContentIngestionService(CanonicalDocumentValidator validator, ChunkingService chunking,
                                   DocumentRepository documents, DocumentChunkRepository chunks) {
        this.validator = validator;
        this.chunking = chunking;
        this.documents = documents;
        this.chunks = chunks;
    }

    /**
     * @param rawJson the request body verbatim — stored byte-exact, the parser's
     *                sealed output is the record (§8), not a re-serialization
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

        List<ChunkDraft> drafts = chunking.chunk(doc);

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
                    draft.tokenEstimate()));
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

    public record IngestionResult(UUID id, String documentId, boolean duplicate, int chunks,
                                  int elements, int pages, int totalChunks) {
    }
}

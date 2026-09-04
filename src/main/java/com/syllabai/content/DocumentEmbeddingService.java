package com.syllabai.content;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Embeds a document's pending chunks (T-013). Idempotent: only chunks with
 * {@code embedding IS NULL} are processed, so re-running after a partial failure
 * (rate limit, provider outage) resumes instead of duplicating. There is no
 * failover by design — a mixed-model index is inconsistent, so an unavailable
 * provider surfaces as a loud failure and the operation is simply retried later.
 */
@Service
public class DocumentEmbeddingService {

    private final ObjectProvider<EmbeddingProvider> provider;
    private final DocumentChunkRepository chunks;
    private final ChunkVectorRepository vectors;
    private final DocumentRepository documents;

    public DocumentEmbeddingService(ObjectProvider<EmbeddingProvider> provider,
                                    DocumentChunkRepository chunks,
                                    ChunkVectorRepository vectors,
                                    DocumentRepository documents) {
        this.provider = provider;
        this.chunks = chunks;
        this.vectors = vectors;
        this.documents = documents;
    }

    @Transactional
    public EmbeddingResult embedDocument(UUID documentRowId) {
        EmbeddingProvider embedding = requireProvider();

        Document document = documents.findById(documentRowId)
                .orElseThrow(() -> new com.syllabai.shared.NotFoundException(
                        "Document", documentRowId));

        List<DocumentChunk> pending = chunks.findPendingByDocumentRowId(documentRowId);
        int embedded = 0;
        for (DocumentChunk chunk : pending) {
            float[] vector = embedding.embedDocument(chunk.content());
            if (vector == null || vector.length != embedding.dimension()) {
                throw new IllegalStateException("embedding provider " + embedding.model()
                        + " returned " + (vector == null ? "null" : vector.length)
                        + " dimensions, expected " + embedding.dimension()
                        + " — refusing to store an inconsistent vector");
            }
            vectors.storeEmbedding(chunk.id(), vector, embedding.model());
            chunk.markEmbedded(embedding.model(), java.time.Instant.now());
            embedded++;
        }
        return new EmbeddingResult(documentRowId, document.documentId(), embedding.model(),
                embedded, (int) chunks.countByDocumentRowId(documentRowId) - embedded,
                (int) chunks.countByDocumentRowId(documentRowId));
    }

    private EmbeddingProvider requireProvider() {
        EmbeddingProvider embedding = provider.getIfAvailable();
        if (embedding == null) {
            throw new IllegalStateException(
                    "no embedding provider configured — set SYLLABAI_EMBEDDING_GEMINI_API_KEY "
                            + "(free tier, ADR-009); ingestion and chunking work without it, "
                            + "embedding does not run until a key is present");
        }
        return embedding;
    }

    public record EmbeddingResult(UUID documentRowId, String documentId, String model,
                                  int embedded, int skipped, int totalChunks) {
    }
}

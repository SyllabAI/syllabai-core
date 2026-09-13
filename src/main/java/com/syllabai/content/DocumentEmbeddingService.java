package com.syllabai.content;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Embeds a document's pending chunks (T-013). Idempotent: only chunks with
 * {@code embedding IS NULL} are processed, so re-running after a partial failure
 * (rate limit, provider outage) resumes instead of duplicating. There is no
 * failover by design — a mixed-model index is inconsistent, so an unavailable
 * provider surfaces as a loud failure and the operation is simply retried later.
 *
 * <p>Transaction shape (C-5): provider calls run <em>outside</em> any database
 * transaction — a slow or hung embedding request must not hold a pooled
 * connection (the provider itself is call-bounded via
 * {@code syllabai.embedding.gemini.timeout-seconds}). The store phase then commits
 * in one {@link TransactionTemplate} transaction, preserving the previous
 * all-or-nothing semantics: a failure mid-store leaves every chunk pending, so
 * the documented idempotent re-run resumes cleanly. When invoked inside an
 * existing transaction (e.g. the GLM-OCR ingestion flow), the store phase joins
 * the caller's transaction as before.</p>
 */
@Service
public class DocumentEmbeddingService {

    private final ObjectProvider<EmbeddingProvider> provider;
    private final DocumentChunkRepository chunks;
    private final ChunkVectorRepository vectors;
    private final DocumentRepository documents;
    private final TransactionTemplate tx;

    public DocumentEmbeddingService(ObjectProvider<EmbeddingProvider> provider,
                                    DocumentChunkRepository chunks,
                                    ChunkVectorRepository vectors,
                                    DocumentRepository documents,
                                    TransactionTemplate tx) {
        this.provider = provider;
        this.chunks = chunks;
        this.vectors = vectors;
        this.documents = documents;
        this.tx = tx;
    }

    public EmbeddingResult embedDocument(UUID documentRowId) {
        EmbeddingProvider embedding = requireProvider();

        Document document = documents.findById(documentRowId)
                .orElseThrow(() -> new com.syllabai.shared.NotFoundException(
                        "Document", documentRowId));

        List<DocumentChunk> pending = chunks.findPendingByDocumentRowId(documentRowId);

        // Embed + validate everything first, holding no transaction: a provider
        // outage or dimension mismatch here has stored nothing (same observable
        // outcome as the previous end-to-end transaction rolling back).
        List<Embedded> embedded = new ArrayList<>(pending.size());
        for (DocumentChunk chunk : pending) {
            float[] vector = embedding.embedDocument(chunk.content());
            if (vector == null || vector.length != embedding.dimension()) {
                throw new IllegalStateException("embedding provider " + embedding.model()
                        + " returned " + (vector == null ? "null" : vector.length)
                        + " dimensions, expected " + embedding.dimension()
                        + " — refusing to store an inconsistent vector");
            }
            embedded.add(new Embedded(chunk, vector));
        }

        tx.executeWithoutResult(status -> {
            for (Embedded e : embedded) {
                vectors.storeEmbedding(e.chunk().id(), e.vector(), embedding.model());
                e.chunk().markEmbedded(embedding.model(), Instant.now());
                chunks.save(e.chunk());
            }
        });

        return new EmbeddingResult(documentRowId, document.documentId(), embedding.model(),
                embedded.size(), (int) chunks.countByDocumentRowId(documentRowId) - embedded.size(),
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

    private record Embedded(DocumentChunk chunk, float[] vector) {
    }

    public record EmbeddingResult(UUID documentRowId, String documentId, String model,
                                  int embedded, int skipped, int totalChunks) {
    }
}

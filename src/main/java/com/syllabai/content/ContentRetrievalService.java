package com.syllabai.content;

import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Vector retrieval over the chunk index (§13, T-013) — the substrate T-024 (KA-RAG
 * hybrid retrieval) builds on. Content-side only: this answers "which canonical
 * chunks are most similar to this query", never generates tutor answers (the
 * content-first rule: no chat surface until the content spine is validated).
 */
@Service
public class ContentRetrievalService {

    private static final int MAX_LIMIT = 50;

    private final ObjectProvider<EmbeddingProvider> provider;
    private final ChunkVectorRepository vectors;

    public ContentRetrievalService(ObjectProvider<EmbeddingProvider> provider,
                                   ChunkVectorRepository vectors) {
        this.provider = provider;
        this.vectors = vectors;
    }

    public List<ChunkHit> search(String query, Document.Kind kind, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        EmbeddingProvider embedding = provider.getIfAvailable();
        if (embedding == null) {
            throw new IllegalStateException(
                    "no embedding provider configured — set SYLLABAI_EMBEDDING_GEMINI_API_KEY "
                            + "(free tier, ADR-009); search is unavailable until keyed");
        }
        int boundedLimit = Math.clamp(limit, 1, MAX_LIMIT);
        float[] queryVector = embedding.embedQuery(query.strip());
        if (queryVector == null || queryVector.length != embedding.dimension()) {
            throw new IllegalStateException("embedding provider " + embedding.model()
                    + " returned an inconsistent query vector");
        }
        return vectors.search(queryVector, kind, boundedLimit);
    }
}

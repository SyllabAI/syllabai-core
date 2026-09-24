package com.syllabai.content;

import com.syllabai.curriculum.CurriculumScope;
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

    /**
     * Curriculum-scoped, VALIDATED-only vector search (T-C07 + T-C05/T-C20):
     * only chunks whose document resolves into {@code scope}'s curriculum
     * version are eligible, and only from a {@code VALIDATED} paper (paper
     * branch) or a {@code VALIDATED} document (knowledge-layer subject
     * branch) — the same serving-eligible boundary the lexical arm enforces
     * via {@link ChunkLexicalRepository#searchServingEligible}. Null scope is
     * rejected before anything runs — retrieval never serves unscoped, and an
     * unresolvable curriculum is the caller's refusal, not a wildcard here.
     *
     * <p>The neutral {@link ChunkVectorRepository#search} remains the
     * benchmark/audit surface (T-C13 arm replay); this serving path is the
     * VALIDATED-only surface the T-C20 closure owns.</p>
     */
    public List<ChunkHit> search(String query, Document.Kind kind, CurriculumScope scope, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("curriculum scope is mandatory — search never runs unscoped (T-C07)");
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
        return vectors.searchServingEligible(queryVector, kind, scope.curriculumVersionId(),
                boundedLimit);
    }
}

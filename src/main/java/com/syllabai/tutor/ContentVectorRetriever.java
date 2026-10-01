package com.syllabai.tutor;

import com.syllabai.content.ChunkHit;
import com.syllabai.content.ContentRetrievalService;
import com.syllabai.curriculum.CurriculumScope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Vector-side retrieval adapter (T-024): wraps the T-013 content retrieval
 * service and lifts its {@link ChunkHit}s into {@link EvidenceItem}s — an
 * Adapter (Master Spec §23) keeping the content module unaware of the tutor.
 *
 * <p>Kind-agnostic by design: a learner question may be answered by a mark
 * scheme, the specification or a worked question — the fuser decides which
 * source wins, not a hard-coded filter. Candidates below the cosine floor
 * are dropped: pgvector always returns the top-N regardless of relevance,
 * and rank-only fusion cannot express "the vector side found nothing" — a
 * zero-similarity chunk would otherwise tie with genuine KG evidence. When
 * no embedding provider is configured the adapter degrades honestly: empty
 * candidates, KG-only evidence, logged once per failure (never silently
 * retried).</p>
 *
 * <p>Document version provenance is carried by the search SQL itself
 * ({@code documents.doc_version} joined in
 * {@link com.syllabai.content.ChunkVectorRepository#searchServingEligible}):
 * the adapter reads {@code hit.docVersion()} directly and never re-reads the
 * document row per hit — the per-hit {@code findById()} this class used to
 * perform was a T-C32-class N+1 on the serving path, one extra query per
 * evidence candidate, every ask.</p>
 */
@Component
public class ContentVectorRetriever implements VectorRetriever {

    private static final Logger log = LoggerFactory.getLogger(ContentVectorRetriever.class);

    /** cosine floor for chunk candidacy (below = not evidence, however ranked) */
    static final double MIN_COSINE = 0.15;

    private final ContentRetrievalService retrieval;

    public ContentVectorRetriever(ContentRetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @Override
    public List<EvidenceItem> retrieve(String query, int limit, CurriculumScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("curriculum scope is mandatory — retrieval never runs unscoped (T-C07)");
        }
        try {
            List<ChunkHit> hits = retrieval.search(query, null, scope, limit);
            return hits.stream()
                    .filter(hit -> hit.score() >= MIN_COSINE)
                    .map(hit -> EvidenceItem.fromChunk(
                            hit.documentRowId(), hit.documentId(), hit.docVersion(),
                            hit.chunkId(), hit.chunkIndex(), hit.kind(), hit.content(),
                            hit.pageStart(), hit.pageEnd(), hit.elementIds(),
                            hit.embeddingModel(), hit.score()))
                    .toList();
        } catch (IllegalStateException e) {
            // no embedding provider keyed (or inconsistent provider output) —
            // document evidence unavailable, not a pipeline failure
            log.warn("vector retrieval unavailable: {}", e.getMessage());
            return List.of();
        }
    }
}

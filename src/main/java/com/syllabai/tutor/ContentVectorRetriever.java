package com.syllabai.tutor;

import com.syllabai.content.ChunkHit;
import com.syllabai.content.ContentRetrievalService;
import com.syllabai.content.DocumentRepository;
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
 */
@Component
public class ContentVectorRetriever implements VectorRetriever {

    private static final Logger log = LoggerFactory.getLogger(ContentVectorRetriever.class);

    /**
     * Cosine floor for chunk candidacy (below = not evidence, however ranked).
     *
     * <p>CALIBRATED 0.15 &rarr; 0.50 (T-C42, 2026-10-02): both flip gates of the
     * calibration pack (master `evidence/bench-001/cosine-calibration-2026-10-01/`)
     * are recorded PASS — gate 1, the run-005-c-r8 re-record (recall unchanged,
     * &sect;8(d) byte-identical, MRR/nDCG slightly improved, zero-result 0/120);
     * gate 2, the production-space probe on the Neon serving pool (embed_rev=2,
     * n=2,935): PRB-01 canonical pct_above_050 = 59.5% (pack binding rule: mass
     * above 0.50), histogram peak in the 0.50&ndash;0.55 bucket, recorded in the
     * pack's dated addendum. 0.15 measured as a no-op floor (100% of the corpus
     * clears it); refusal semantics below 0.50 return to the pool via the
     * retrieval refusal rate, monitored post-deploy.</p> */
    static final double MIN_COSINE = 0.50;

    private final ContentRetrievalService retrieval;
    private final DocumentRepository documents;

    public ContentVectorRetriever(ContentRetrievalService retrieval,
                                  DocumentRepository documents) {
        this.retrieval = retrieval;
        this.documents = documents;
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
                            hit.documentRowId(), hit.documentId(), documentVersion(hit),
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

    private int documentVersion(ChunkHit hit) {
        if (hit.documentRowId() == null) {
            return 1;
        }
        return documents.findById(hit.documentRowId())
                .map(d -> d.docVersion())
                .orElse(1);
    }
}

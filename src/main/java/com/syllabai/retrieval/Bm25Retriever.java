package com.syllabai.retrieval;

import com.syllabai.content.ChunkHit;
import com.syllabai.content.ChunkLexicalRepository;
import com.syllabai.content.DocumentRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The lexical (BM25-style) candidate provider — arm B of the T-C13 benchmark,
 * the research doc §7 P0 lexical retrieval (T-C14). Postgres full-text search
 * over {@code document_chunks.content_tsv} (flyway V28): no new infrastructure,
 * free-tier preserved.
 *
 * <p>Contract (prepared package §3, ratified-on-merge):
 * {@code websearch_to_tsquery('english', :normalizedQuery)} parses the query,
 * {@code ts_rank_cd} orders it, the T-C07 curriculum predicate is mandatory,
 * the candidate bound comes from {@link StructuredRetrievalQuery#limit()}, and
 * an empty/blank query fails closed to an empty result before any SQL runs.
 * Fusion, when arm C promotes it, reuses the existing
 * {@code ReciprocalRankFusion} (k=60) — no new fusion code.</p>
 *
 * <p>Boundary note (T-C05, operator directive): the arm searches through
 * {@link ChunkLexicalRepository#searchServingEligible} — the owning paper must
 * be {@code VALIDATED}, so a SUGGESTED/FLAGGED/REJECTED paper's chunks are
 * unreachable by BM25 even before any central enforcement exists. The fabric's
 * "enforced once, centrally" invariant governs the future serving wiring and
 * supersedes (never weakens) this arm-level guard; until that enforcer lands,
 * the benchmark arm is compliant by construction and the T-C13 harness scores
 * any surfaced SUGGESTED-only chunk as a hard violation.</p>
 *
 * <p>Honest scope notes: {@link #available()} is {@code true} — the provider's
 * only dependency (the V28 column) is owned by flyway and applied before app
 * startup in every environment running this code; a database lacking it fails
 * loudly at the SQL layer, not silently. Candidates carry
 * {@code validationStatus == null}: the chunk join does not expose paper
 * validation state, so the provider refuses to claim one — the
 * validation boundary is enforced once, centrally, and never per-provider
 * (contract invariant 1). Serving wiring is untouched: BM25 enters the served
 * fusion only if the T-C13 benchmark promotes it.</p>
 */
@Component
public class Bm25Retriever implements RetrievalProvider {

    private static final Logger log = LoggerFactory.getLogger(Bm25Retriever.class);

    private final ChunkLexicalRepository lexical;
    private final DocumentRepository documents;

    public Bm25Retriever(ChunkLexicalRepository lexical, DocumentRepository documents) {
        this.lexical = lexical;
        this.documents = documents;
    }

    @Override
    public String id() {
        return "bm25";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
        java.util.Objects.requireNonNull(query, "query");
        if (query.normalizedQuery() == null || query.normalizedQuery().isBlank()) {
            return List.of();
        }
        List<ChunkHit> hits = lexical.searchServingEligible(query.normalizedQuery(), query.resourceKinds(),
                query.curriculumVersionId(), query.limit());
        return hits.stream()
                .map(this::toCandidate)
                .toList();
    }

    private RetrievalCandidate toCandidate(ChunkHit hit) {
        return new RetrievalCandidate(
                id(),
                hit.documentRowId(),
                hit.documentId(),
                documentVersion(hit),
                String.valueOf(hit.chunkId()),
                null,
                null,
                hit.content(),
                hit.score(),
                null,
                null,
                java.util.Map.of(
                        "chunk_index", String.valueOf(hit.chunkIndex()),
                        "document_kind", hit.kind() == null ? "OTHER" : hit.kind()));
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

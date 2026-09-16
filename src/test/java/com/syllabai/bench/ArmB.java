package com.syllabai.bench;

import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-C13 arm B (spec §2): the production lexical provider —
 * {@code com.syllabai.retrieval.Bm25Retriever} (T-C14: Postgres FTS
 * tsvector+GIN V28, websearch_to_tsquery 'english', ts_rank_cd, T-C07 scope
 * predicate, T-C05 VALIDATED-paper serving via the serving-eligible surface) —
 * executed through the {@link RetrievalProvider} fabric contract against a
 * REAL Postgres prepared from the frozen snapshot.
 *
 * <p>Unlike A0 (pure snapshot replay), arm B's native substrate is the
 * production SQL path, so its recorded run drives the production provider code
 * over a Flyway-migrated database loaded with snap-001's corpus — the
 * measurement is the production code, not a port of it.</p>
 *
 * <p>Portable identity: gold chunk refs are {@code (document checksum,
 * chunk ordinal)}; the bench loader writes {@code documents.document_id =
 * checksum} and {@code document_chunks.chunk_index = ordinal}, and the
 * provider's candidates carry {@code documentId} + {@code metadata["chunk_index"]}
 * — so {@code documentId + ":" + chunkIndex} reconstructs the gold chunk_ref
 * exactly (spec §3.3 portable evidence identity).</p>
 *
 * <p>Boundary audit: every returned candidate is checked against the loader's
 * paper-state map — any candidate whose paper is not VALIDATED is a
 * VALIDATION_BOUNDARY_VIOLATION (spec §5.1 hard fail for the run). By
 * construction the production serving-eligible predicate already excludes
 * them; the audit proves the predicate held under the actual run.</p>
 */
public final class ArmB {

    public record BResult(List<String> rankedRefs, List<Double> scores,
                          int boundaryViolations, List<String> violationRefs) {
    }

    private final RetrievalProvider provider;
    private final QueryFactory queries;
    private final Map<String, String> paperStateByDocumentId;

    /** Minimal adapter so the arm owns one construction site (query + scope pinned). */
    public interface QueryFactory {
        StructuredRetrievalQuery create(String query, int limit);
    }

    public ArmB(RetrievalProvider provider, com.syllabai.curriculum.CurriculumScope scope,
                Map<String, String> paperStateByDocumentId) {
        this(provider, (q, limit) -> StructuredRetrievalQuery.of(q, scope, limit), paperStateByDocumentId);
    }

    public ArmB(RetrievalProvider provider, QueryFactory queries,
                Map<String, String> paperStateByDocumentId) {
        this.provider = provider;
        this.queries = queries;
        this.paperStateByDocumentId = Map.copyOf(paperStateByDocumentId);
    }

    /** Runs one query; returns ranked portable refs (best-first) + the boundary audit. */
    public BResult run(String query, int limit) {
        List<RetrievalCandidate> hits = provider.retrieve(queries.create(query, limit));
        List<String> refs = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (RetrievalCandidate hit : hits) {
            refs.add(chunkRef(hit));
            scores.add(hit.providerScore());
            String state = paperStateByDocumentId.get(hit.documentId());
            if (!"VALIDATED".equals(state)) {
                violations.add(chunkRef(hit) + "@" + state);
            }
        }
        return new BResult(List.copyOf(refs), List.copyOf(scores), violations.size(), List.copyOf(violations));
    }

    /** Portable evidence identity (spec §3.3): document checksum + chunk ordinal. */
    public static String chunkRef(RetrievalCandidate candidate) {
        String ordinal = candidate.metadata().getOrDefault("chunk_index", "-1");
        return candidate.documentId() + ":" + ordinal;
    }

    /** Pure boundary audit over a ranked list (unit-testable without a DB). */
    public static List<String> audit(List<String> rankedRefs, Map<String, String> paperStateByDocumentId) {
        List<String> violations = new ArrayList<>();
        for (String ref : rankedRefs) {
            String docId = ref.substring(0, ref.lastIndexOf(':'));
            String state = paperStateByDocumentId.get(docId);
            if (!"VALIDATED".equals(state)) {
                violations.add(ref + "@" + state);
            }
        }
        return violations;
    }

    /** Frequency-ordered helper for per-class aggregation (LinkedHashMap keeps insertion order). */
    public static <T> Map<T, Integer> countBy(List<T> values) {
        Map<T, Integer> counts = new LinkedHashMap<>();
        for (T v : values) {
            counts.merge(v, 1, Integer::sum);
        }
        return counts;
    }
}

package com.syllabai.bench;

import com.syllabai.content.ChunkVectorRepository;
import com.syllabai.content.ContentRetrievalService;
import com.syllabai.content.DocumentRepository;
import com.syllabai.content.EmbeddingProvider;
import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.tutor.ContentVectorRetriever;
import com.syllabai.tutor.EvidenceItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * T-C13 arm A (spec §2): the production semantic arm — the REAL serving path
 * {@code ContentVectorRetriever → ContentRetrievalService →
 * ChunkVectorRepository.search} (pgvector cosine {@code <=>} over the V11
 * {@code vector(768)} column, T-C07 scope EXISTS predicate, cosine floor 0.15,
 * kind-agnostic, NoReranker) executed against a REAL Postgres migrated
 * V1..V28 and loaded with the frozen snap-001 corpus, with the frozen
 * backfill artifact applied (embed-backfill-snap-001).
 *
 * <p>Compute-once-freeze-forever (sessions 92/94): the ONLY non-production
 * surface is the embedding call itself. Query vectors are served from the
 * frozen artifact through the production {@code EmbeddingProvider} port by
 * {@link FrozenQueryVectors}; a query text with no frozen vector is a hard
 * failure ({@link MissingFrozenVectorException}, deliberately NOT an
 * {@code IllegalStateException} — the production retriever degrades
 * {@code IllegalStateException} honestly to an empty list, and a silent empty
 * would corrupt the measurement). Chunk vectors are the artifact's, stored
 * through the real {@link ChunkVectorRepository#storeEmbedding}. Zero API
 * calls at run time; the run is byte-reproducible.</p>
 *
 * <p>Portable identity: {@code documentId + ":" + chunkIndex} — the same
 * {@code (document checksum, chunk ordinal)} refs the gold set uses (the
 * bench loader writes {@code documents.document_id = checksum} and
 * {@code document_chunks.chunk_index = ordinal}). Boundary audit reuses
 * {@link ArmB#audit} verbatim: every returned hit is checked against the
 * loader's paper-state map; any non-VALIDATED hit is a recorded
 * VALIDATION_BOUNDARY_VIOLATION finding. The production vector surface
 * predates T-C05 (the VALIDATED predicate exists on the lexical
 * serving-eligible surface only), so the served view is expected to surface
 * the finding; the {@link #compliantView post-hoc VALIDATED-only view} is the
 * evaluation view comparable with arm B's compliant scope. Neither view is a
 * promotion claim.</p>
 */
public final class ArmA {

    /** Hard failure for a query text missing from the frozen artifact (fail-closed). */
    static final class MissingFrozenVectorException extends RuntimeException {
        MissingFrozenVectorException(String msg) {
            super(msg);
        }
    }

    public record AResult(List<String> rankedRefs, List<Double> scores,
                          int boundaryViolations, List<String> violationRefs) {
    }

    private final ContentVectorRetriever retriever;
    private final CurriculumScope scope;
    private final Map<String, String> paperStateByDocumentId;

    public ArmA(ContentVectorRetriever retriever, CurriculumScope scope,
                Map<String, String> paperStateByDocumentId) {
        this.retriever = retriever;
        this.scope = scope;
        this.paperStateByDocumentId = Map.copyOf(paperStateByDocumentId);
    }

    /** Runs one query through the production semantic path; refs best-first + boundary audit. */
    public AResult run(String query, int limit) {
        List<EvidenceItem> hits = retriever.retrieve(query, limit, scope);
        List<String> refs = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (EvidenceItem hit : hits) {
            refs.add(chunkRef(hit));
            scores.add(hit.retrievalScore());
        }
        List<String> violations = ArmB.audit(refs, paperStateByDocumentId);
        return new AResult(List.copyOf(refs), List.copyOf(scores),
                violations.size(), List.copyOf(violations));
    }

    /** Portable evidence identity (spec §3.3): document checksum + chunk ordinal. */
    static String chunkRef(EvidenceItem item) {
        if (item.documentId() == null || item.chunkIndex() == null) {
            throw new IllegalStateException("chunk hit without portable identity: " + item);
        }
        return item.documentId() + ":" + item.chunkIndex();
    }

    /**
     * Post-hoc VALIDATED-only view: the served ranked list filtered to
     * VALIDATED-paper chunks, order preserved. This is the run-001 B-proxy
     * {@code validated_only} discipline — an EVALUATION view for comparability
     * with arm B's compliant scope, NOT a serving simulation (a compliant
     * vector surface would re-rank within the compliant corpus and is not
     * implemented here; the T-C05 closure is a registered follow-up).
     */
    public static AResult compliantView(AResult served, Map<String, String> paperStateByDocumentId) {
        List<String> refs = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < served.rankedRefs().size(); i++) {
            String ref = served.rankedRefs().get(i);
            String docId = ref.substring(0, ref.lastIndexOf(':'));
            if ("VALIDATED".equals(paperStateByDocumentId.get(docId))) {
                refs.add(ref);
                scores.add(served.scores().get(i));
            }
        }
        return new AResult(List.copyOf(refs), List.copyOf(scores), 0, List.of());
    }

    /**
     * The frozen query-vector provider: serves artifact vectors through the
     * production {@code EmbeddingProvider} port. Lookup key is the query text
     * stripped exactly as {@code ContentRetrievalService} strips before the
     * embed call. Vectors are cloned on the way out (the frozen artifact is
     * immutable state). Fail-closed on any unseen text.
     */
    static final class FrozenQueryVectors implements EmbeddingProvider {

        private final String model;
        private final int dimension;
        private final Map<String, float[]> byStrippedQuery;

        FrozenQueryVectors(String model, int dimension, Map<String, float[]> byStrippedQuery) {
            this.model = model;
            this.dimension = dimension;
            this.byStrippedQuery = Map.copyOf(byStrippedQuery);
        }

        @Override
        public String model() {
            return model;
        }

        @Override
        public int dimension() {
            return dimension;
        }

        @Override
        public float[] embedDocument(String text) {
            throw new IllegalStateException("arm A replay never embeds documents (compute-once-freeze-forever)");
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            throw new IllegalStateException("arm A replay never embeds documents (compute-once-freeze-forever)");
        }

        @Override
        public float[] embedQuery(String text) {
            if (text == null) {
                // deliberately NOT IllegalStateException: the production retriever
                // degrades that to an honest empty list — a silent empty would
                // corrupt the measurement. This must surface as a hard failure.
                throw new MissingFrozenVectorException(
                        "no frozen query vector for a null query text (fail-closed)");
            }
            float[] v = byStrippedQuery.get(text.strip());
            if (v == null) {
                // deliberately NOT IllegalStateException: the production retriever
                // degrades that to an honest empty list — a silent empty would
                // corrupt the measurement. This must surface as a hard failure.
                throw new MissingFrozenVectorException(
                        "no frozen query vector for the stripped query text (fail-closed)");
            }
            return v.clone();
        }
    }

    /**
     * The production retrieval stack wired for the bench: real
     * {@code ContentRetrievalService} with the frozen provider behind the
     * {@code ObjectProvider<EmbeddingProvider>} seam (reflective stub, the
     * Run003B {@code stubDocumentRepository} pattern) and the real
     * {@code ChunkVectorRepository} over the bench JDBC template.
     */
    static ContentVectorRetriever productionRetriever(JdbcTemplateHolder holder,
                                                      EmbeddingProvider frozenProvider) {
        ContentRetrievalService service = new ContentRetrievalService(
                objectProvider(frozenProvider), new ChunkVectorRepository(holder.jdbc()));
        return new ContentVectorRetriever(service, stubDocumentRepository());
    }

    /** Minimal ObjectProvider stub — the service only calls getIfAvailable(). */
    private static org.springframework.beans.factory.ObjectProvider<EmbeddingProvider> objectProvider(
            EmbeddingProvider provider) {
        return (org.springframework.beans.factory.ObjectProvider<EmbeddingProvider>) java.lang.reflect.Proxy
                .newProxyInstance(
                        org.springframework.beans.factory.ObjectProvider.class.getClassLoader(),
                        new Class<?>[]{org.springframework.beans.factory.ObjectProvider.class},
                        (proxy, method, methodArgs) -> {
                            switch (method.getName()) {
                                case "getIfAvailable":
                                case "getObject":
                                    return provider;
                                case "equals":
                                    return proxy == methodArgs[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "bench-frozen-query-vector-provider";
                                default: {
                                    Class<?> t = method.getReturnType();
                                    if (t == boolean.class) {
                                        return false;
                                    }
                                    if (t == int.class) {
                                        return 0;
                                    }
                                    if (t == long.class) {
                                        return 0L;
                                    }
                                    return null;
                                }
                            }
                        });
    }

    /** Same stub as Run003B: bench corpus is doc_version 1 — findById → empty → fallback 1. */
    private static DocumentRepository stubDocumentRepository() {
        return (DocumentRepository) java.lang.reflect.Proxy.newProxyInstance(
                DocumentRepository.class.getClassLoader(),
                new Class<?>[]{DocumentRepository.class},
                (proxy, method, methodArgs) -> {
                    switch (method.getName()) {
                        case "findById":
                            return java.util.Optional.empty();
                        case "equals":
                            return proxy == methodArgs[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "bench-stub-document-repository";
                        default: {
                            Class<?> t = method.getReturnType();
                            if (t == boolean.class) {
                                return false;
                            }
                            if (t == int.class) {
                                return 0;
                            }
                            if (t == long.class) {
                                return 0L;
                            }
                            if (java.util.Optional.class == t) {
                                return java.util.Optional.empty();
                            }
                            if (java.util.List.class == t) {
                                return List.of();
                            }
                            return null;
                        }
                    }
                });
    }

    /** Tiny holder so the wiring method's signature stays framework-free. */
    record JdbcTemplateHolder(org.springframework.jdbc.core.JdbcTemplate jdbc) {
    }
}

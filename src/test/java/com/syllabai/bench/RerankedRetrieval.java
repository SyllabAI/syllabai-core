package com.syllabai.bench;

import com.syllabai.retrieval.RetrievalFabric;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Arm D's composition seam (T-C63 rank-quality lane): a {@link RetrievalFabric}
 * whose fused output is then reranked behind the PRODUCTION
 * {@link EvidenceReranker} port (registry row D: "C + reranker behind the
 * EvidenceReranker port").
 *
 * <p><strong>Boundary discipline (§8(f) is structurally unaffected):</strong>
 * the serving boundary lives inside the inner fabric — applied pre-fusion,
 * exactly once, unchanged. This wrapper sees only what the fabric already
 * produced: the reranker can REORDER the fused candidates and nothing else —
 * it cannot admit a candidate the boundary excluded, cannot invent one, and
 * cannot drop one (identity-checked below, fail-closed).</p>
 *
 * <p><strong>Bridge honesty:</strong> the candidates→{@link EvidenceItem}
 * projection here is a SCORING bridge, not the fabric's production projection
 * ({@code RetrievalFabric.toEvidence} stays private): every item carries the
 * candidate's content and rides its {@code evidenceLocator} in
 * {@code documentId} for the identity-checked bridge back. All items project
 * with source {@code OTHER} on purpose — the pre-registered reranker is
 * source-blind (no kind-awareness; charter), so a kind-tagged projection
 * would be dead data inviting accidental use.</p>
 *
 * <p>Deterministic: the inner fabric is deterministic, the reranker is pure;
 * identical queries rerank identically (the bench double-pass contract holds
 * by construction).</p>
 */
public final class RerankedRetrieval {

    /**
     * The seam Run005C consumes: only {@code retrieve} is needed by the
     * scoring loop, so the NoReranker path can stay the bare fabric method
     * reference (absent-path byte-identity by construction).
     */
    public interface FusedRetriever {
        List<RetrievalFabric.FusedCandidate> retrieve(StructuredRetrievalQuery query);
    }

    private final RetrievalFabric inner;
    private final EvidenceReranker reranker;

    private RerankedRetrieval(RetrievalFabric inner, EvidenceReranker reranker) {
        this.inner = Objects.requireNonNull(inner, "inner fabric");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
    }

    /**
     * The NoReranker path stays the bare fabric (never wrapped): a null
     * reranker returns the fabric's own method reference, so the recorded
     * arm-C behavior is untouched by construction (absent-path identity).
     */
    public static FusedRetriever maybeWrap(RetrievalFabric fabric, EvidenceReranker reranker) {
        Objects.requireNonNull(fabric, "fabric");
        if (reranker == null) {
            return fabric::retrieve;
        }
        return new RerankedRetrieval(fabric, reranker)::retrieve;
    }

    private List<RetrievalFabric.FusedCandidate> retrieve(StructuredRetrievalQuery query) {
        List<RetrievalFabric.FusedCandidate> fused = inner.retrieve(query);
        if (fused.isEmpty()) {
            return fused;
        }

        Map<String, RetrievalFabric.FusedCandidate> byLocator = new LinkedHashMap<>();
        List<EvidenceItem> items = new ArrayList<>(fused.size());
        for (RetrievalFabric.FusedCandidate candidate : fused) {
            String locator = candidate.candidate().evidenceLocator();
            byLocator.put(locator, candidate);
            items.add(new EvidenceItem(EvidenceItem.EvidenceSource.OTHER,
                    candidate.candidate().content(), null, locator, null, null, null,
                    null, null, null, null, null, null, List.of(), List.of(),
                    candidate.candidate().providerScore(), candidate.fusedScore(), null));
        }

        List<EvidenceItem> reranked = reranker.rerank(query.normalizedQuery(), items);

        List<RetrievalFabric.FusedCandidate> out = new ArrayList<>(reranked.size());
        for (EvidenceItem item : reranked) {
            RetrievalFabric.FusedCandidate candidate = byLocator.get(item.documentId());
            if (candidate == null) {
                throw new IllegalStateException("reranked item without a tracked candidate: "
                        + item.documentId() + " (reranker invented or re-identified a "
                        + "candidate — port-contract breach, fail-closed)");
            }
            out.add(candidate);
        }
        if (out.size() != fused.size()) {
            throw new IllegalStateException("reranker changed the candidate count: "
                    + fused.size() + " -> " + out.size() + " (port-contract breach, fail-closed)");
        }
        return List.copyOf(out);
    }

    /** The reranker registry behind BENCH_ARM_D_RERANKER (fail-closed on unknown specs). */
    public static EvidenceReranker rerankerForSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        if (LexicalPrecisionReranker.envSpec().equals(spec.trim())) {
            return new LexicalPrecisionReranker();
        }
        throw new IllegalStateException("unknown BENCH_ARM_D_RERANKER spec: '" + spec
                + "' (known: " + LexicalPrecisionReranker.envSpec() + ") — fail-closed");
    }
}

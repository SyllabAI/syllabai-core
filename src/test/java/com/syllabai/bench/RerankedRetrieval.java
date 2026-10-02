package com.syllabai.bench;

import com.syllabai.retrieval.RetrievalCandidate;
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
 * T-C63 arm D wrapper (bench scope): a {@link RetrievalFabric} + an
 * {@link EvidenceReranker} composed into one {@code retrieve()} with the SAME
 * output shape as the fabric itself. Per the charter: {@code retrieve()} =
 * fabric output → bridge to {@link EvidenceItem}s (locator in {@code documentId}
 * for the bridge back) → {@code rerank(query, items)} → reorder the SAME
 * {@link RetrievalFabric.FusedCandidate}s.
 *
 * <p><strong>Where the boundary lives:</strong> inside the inner fabric
 * (pre-fusion, untouched). The wrapper runs strictly downstream of fusion and
 * can only reorder what fusion produced — no new fusion code, no boundary
 * change — so the §8(f) boundary audit of the wrapped run is structurally
 * unaffected: whatever fusion emitted is all that can come back.</p>
 *
 * <p><strong>Bench scope only:</strong> nothing in production constructs a
 * reranking fabric (harness spec §9 guardrail; the production fabric remains
 * rank-only with the {@code NoReranker} default). This class is not a Spring
 * bean and cannot leak into the serving path.</p>
 *
 * <p><strong>Determinism:</strong> the wrapper is a pure function of
 * query × fabric output — the run's double-pass determinism contract covers the
 * reranked path byte-identically (or the run aborts).</p>
 */
final class RerankedRetrieval {

    private final RetrievalFabric fabric;
    private final EvidenceReranker reranker;

    RerankedRetrieval(RetrievalFabric fabric, EvidenceReranker reranker) {
        this.fabric = Objects.requireNonNull(fabric, "fabric");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
    }

    /**
     * Fused candidates, reranked best-first. Same candidates, possibly
     * reordered — never invented, never dropped, never re-scored on the fused
     * axis (the recorded {@code fusedScore} stays the fusion's own score; the
     * reranker's scores live on the transient evidence items only).
     */
    List<RetrievalFabric.FusedCandidate> retrieve(StructuredRetrievalQuery query) {
        Objects.requireNonNull(query, "query");
        List<RetrievalFabric.FusedCandidate> fused = fabric.retrieve(query);
        if (fused.isEmpty()) {
            return fused;
        }

        Map<String, RetrievalFabric.FusedCandidate> byLocator = new LinkedHashMap<>();
        List<EvidenceItem> items = new ArrayList<>(fused.size());
        for (RetrievalFabric.FusedCandidate f : fused) {
            String locator = f.candidate().evidenceLocator();
            RetrievalFabric.FusedCandidate prior = byLocator.putIfAbsent(locator, f);
            if (prior != null) {
                throw new IllegalStateException("duplicate fused locator: " + locator
                        + " (the fabric deduplicates on locators — this is a composition "
                        + "error, fail-closed)");
            }
            items.add(bridge(f, locator));
        }

        List<EvidenceItem> reranked = reranker.rerank(query.normalizedQuery(), items);
        if (reranked.size() != items.size()) {
            throw new IllegalStateException("reranker changed pool size " + items.size()
                    + " → " + reranked.size() + " (port-contract breach — rerankers never "
                    + "invent or drop, fail-closed)");
        }

        List<RetrievalFabric.FusedCandidate> out = new ArrayList<>(fused.size());
        for (EvidenceItem item : reranked) {
            String locator = item.documentId();   // the bridge-back key (locator in documentId)
            RetrievalFabric.FusedCandidate f = byLocator.get(locator);
            if (f == null) {
                throw new IllegalStateException("reranked item carries an unknown locator: "
                        + locator + " (port-contract breach — rerankers never invent, fail-closed)");
            }
            out.add(f);
        }
        return List.copyOf(out);
    }

    /**
     * Fabric-port projection into the reranker's evidence shape. The LOCATOR
     * travels in {@code documentId} (the bridge-back key, per the charter) —
     * these items are transient rerank inputs, never served, and their
     * provenance fields are intentionally lean: the pre-registered reranker is
     * query×content and reads nothing else.
     */
    private static EvidenceItem bridge(RetrievalFabric.FusedCandidate f, String locator) {
        RetrievalCandidate c = f.candidate();
        String kind = c.metadata().getOrDefault("document_kind",
                c.knowledgeNodeId() != null ? "KNOWLEDGE_NODE" : "OTHER");
        EvidenceItem.EvidenceSource source = switch (kind) {
            case "MARK_SCHEME" -> EvidenceItem.EvidenceSource.MARK_SCHEME;
            case "QUESTION_PAPER" -> EvidenceItem.EvidenceSource.QUESTION_PAPER;
            case "SYLLABUS" -> EvidenceItem.EvidenceSource.SYLLABUS;
            case "EXTERNAL_NOTES" -> EvidenceItem.EvidenceSource.NOTE;
            case "TEXTBOOK" -> EvidenceItem.EvidenceSource.TEXTBOOK;
            case "EXTERNAL_QUESTIONS" -> EvidenceItem.EvidenceSource.CARD;
            default -> c.knowledgeNodeId() != null
                    ? EvidenceItem.EvidenceSource.KNOWLEDGE_NODE
                    : EvidenceItem.EvidenceSource.OTHER;
        };
        return new EvidenceItem(source, c.content(), c.documentRowId(), locator, c.docVersion(),
                null, null, c.knowledgeNodeId(), c.nodeCode(), null, null,
                null, null, List.of(), List.of(),
                c.providerScore(), f.fusedScore(), null);
    }
}

package com.syllabai.tutor;

import java.util.List;

/**
 * Port: evidence reranking (Master Spec §13 "Evidence fusion / reranking",
 * T-024). A Strategy — implementations are independently replaceable:
 * {@link NoReranker} is the deterministic default; a cross-encoder or
 * external reranker can slot in later without touching the orchestration.
 */
public interface EvidenceReranker {

    /**
     * @param query      the learner's question (some rerankers score query×evidence)
     * @param candidates fused candidates, best-first
     * @return reranked evidence with {@code rerankScore} set; NEVER invents or
     *         drops provenance — only reorders/rescores what fusion produced
     */
    List<EvidenceItem> rerank(String query, List<EvidenceItem> candidates);
}

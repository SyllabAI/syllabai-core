package com.syllabai.tutor;

import java.util.List;

/**
 * Port: vector-side retrieval over the canonical chunk index (Master Spec
 * §13, T-024). Adapts the T-013 content substrate into evidence candidates.
 * Implementations must degrade honestly: when the embedding provider is not
 * configured they return an empty list rather than failing the whole
 * pipeline — KA-RAG then answers from KG evidence alone.
 */
public interface VectorRetriever {

    /**
     * @param query the learner's question
     * @param limit candidate bound (pre-fusion)
     * @return chunk-grounded evidence candidates, best-first (may be empty)
     */
    List<EvidenceItem> retrieve(String query, int limit);
}

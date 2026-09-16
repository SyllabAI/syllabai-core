package com.syllabai.retrieval;

import java.util.List;

/**
 * Contract stub for the Gemini File Search candidate provider (T-C15 — arms
 * E/F/G of the T-C13 benchmark). Exists so the fabric contract is complete and
 * the benchmark registry can reference all arms; it is deliberately NOT a
 * Spring bean — registration and any Google SDK wiring are T-C15 decisions.
 *
 * <p>Honest UNAVAILABLE posture: {@link #available()} is {@code false} and
 * {@link #retrieve(com.syllabai.retrieval.StructuredRetrievalQuery)} returns an
 * empty list — orchestration treats it as provider-down (KG-only degradation),
 * never as "no results". Google types must never leak past the fabric port:
 * whatever T-C15 integrates stays behind this interface, normalized into
 * {@link RetrievalCandidate} (FS doc §10 citation chain) or dropped.</p>
 */
public class GeminiFileSearchRetriever implements RetrievalProvider {

    @Override
    public String id() {
        return "gemini-file-search";
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
        java.util.Objects.requireNonNull(query, "query");
        return List.of();
    }
}

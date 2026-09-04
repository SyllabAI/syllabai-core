package com.syllabai.content;

import java.util.List;

/**
 * Embedding port (Master Spec §13 "implementations remain replaceable", §26 abstraction).
 *
 * <p>Deliberate design points:
 * <ul>
 *   <li><b>No failover.</b> Unlike the chat chain, an embedding index must stay
 *       single-model: vectors from different models are not comparable, so a silent
 *       fallback would poison retrieval. An unavailable provider fails loudly
 *       (ADR-009 honesty rule) and embedding is simply retried later — chunks are
 *       persisted un-embedded first.</li>
 *   <li><b>Asymmetric task types.</b> Documents embed with RETRIEVAL_DOCUMENT,
 *       queries with RETRIEVAL_QUERY — the provider owns that distinction.</li>
 * </ul>
 */
public interface EmbeddingProvider {

    /** Registry identity of the model actually producing vectors (§19). */
    String model();

    /** Vector dimensionality the provider emits — must match the pgvector column. */
    int dimension();

    float[] embedDocument(String text);

    float[] embedQuery(String text);

    /** Batch document embedding — provider may batch internally (rate-limit aware). */
    List<float[]> embedDocuments(List<String> texts);
}

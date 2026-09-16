package com.syllabai.retrieval;

import java.util.List;

/**
 * Port: one candidate-generation arm of the retrieval fabric (T-C14, Master
 * Spec §23 ports-and-adapters; {@code RetrievalProvider_contract_sketch.md}
 * ratified-on-merge). Implementations:
 *
 * <ul>
 *   <li>{@code Bm25Retriever} — Postgres FTS over {@code document_chunks.content_tsv}
 *       (arm B of T-C13; the research doc §7 P0 lexical arm),</li>
 *   <li>{@code PgVectorRetrievalProvider} — adapter over the existing
 *       {@code VectorRetriever} serving port (arm A),</li>
 *   <li>{@code AuthoritativeKgRetrievalProvider} — adapter over the existing
 *       {@code KnowledgeRetriever} serving port,</li>
 *   <li>{@code GeminiFileSearchRetriever} — stub, {@code available() == false}
 *       until T-C15 (Google types must never leak past this interface).</li>
 * </ul>
 *
 * <p>Invariants (enforced outside providers, in fusion/evidence selection —
 * contract sketch §4): rank-only fusion stays score-free (provider scores are
 * logging/benchmark inputs only); validation-boundary exclusion is applied once
 * centrally, never per-provider; {@code curriculumVersionId} is mandatory in
 * every query, making T-C07 un-bypassable by construction; providers degrade
 * honestly (empty list when unavailable, never a pipeline failure).</p>
 */
public interface RetrievalProvider {

    /**
     * Stable provider identity for logging, benchmark manifests and fusion
     * traces: {@code "pgvector"} | {@code "bm25"} | {@code "authoritative-kg"}
     * | {@code "gemini-file-search"}.
     */
    String id();

    /**
     * Whether this provider can currently serve candidates. Honest health
     * signal, checked once per orchestration: a provider that reports
     * {@code false} is skipped (KG-only degradation), never polled. A provider
     * may still return an empty list when {@code true} (e.g. no matches) —
     * unavailability and no-results are distinct states and must stay distinct.
     */
    boolean available();

    /**
     * Returns candidates for the query, best-first (may be empty). The query
     * record is immutable and carries the mandatory curriculum scope; a
     * provider must never widen it. Providers must be deterministic for a
     * given database state — no clock, no random tiebreaks.
     *
     * @param query the structured retrieval query (non-null)
     * @return candidates best-first; empty (never null) when nothing matches
     */
    List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query);
}

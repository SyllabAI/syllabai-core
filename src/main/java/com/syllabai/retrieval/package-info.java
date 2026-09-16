/**
 * The SyllabAI Retrieval Fabric (T-C14): one provider-neutral contract in front
 * of every candidate-generation arm of the Educational Retrieval Engine
 * (ADR-020; {@code RAG_RETRIEVAL_RESEARCH.md} §5/§8/§15 as extended by the
 * Gemini File Search research §6/§15).
 *
 * <p>The fabric port is {@link com.syllabai.retrieval.RetrievalProvider}; the
 * existing serving ports ({@code VectorRetriever}, {@code KnowledgeRetriever})
 * keep their signatures and adapters sit in front of them. Providers return
 * <em>candidates</em> only — nothing a provider returns can create or mutate
 * curriculum relations, and no vendor type crosses the port. Validation-boundary
 * exclusion, fusion and deduplication are enforced once, centrally — never
 * per-provider.</p>
 *
 * <p>Serving wiring: as of T-C14 the KA-RAG serving path is unchanged. Adding
 * a provider to the served fusion is a production retrieval change and is
 * gated on the T-C13 benchmark verdict (no retrieval change becomes a
 * production default without it). Arm B (BM25) is exercised through this
 * contract; the benchmark decides its promotion.</p>
 */
package com.syllabai.retrieval;

package com.syllabai.retrieval;

import java.util.Map;
import java.util.UUID;

/**
 * One candidate emitted by a {@link RetrievalProvider} (T-C14; contract sketch
 * §3) — a retrieval <em>suggestion</em>, never evidence of truth: nothing a
 * provider returns can create or mutate curriculum relations, and
 * {@code providerScore} is a native-score logging/benchmark input (rank-only
 * fusion never reads it).
 *
 * <p><strong>Identity convention:</strong> {@code evidenceLocator} carries the
 * provider's stable identity — the {@code document_chunks.id} UUID string for
 * chunk-backed candidates (lexical/vector), the {@code knowledge_nodes.id}
 * UUID string for KG candidates — the key rank-only fusion deduplicates on.
 * Page ranges and chunk ordinals travel in {@code metadata} as provenance
 * detail. For external providers (File Search) the locator is the
 * provider-native segment identity normalized per FS doc §10 or the candidate
 * is dropped.</p>
 *
 * @param providerId       emitting provider ({@code id()} of the provider)
 * @param documentRowId    documents.id row (null for KG/external candidates)
 * @param documentId       parser-issued canonical document id (best-effort for external)
 * @param docVersion       canonical document version (best-effort; null when unknown)
 * @param evidenceLocator  stable identity per the convention above (non-null)
 * @param knowledgeNodeId  knowledge node id (KG candidates only)
 * @param nodeCode         KG code, e.g. 4CH1-1.26 (KG candidates only)
 * @param content          the candidate text
 * @param providerScore    native score (ts_rank_cd, cosine, match specificity) —
 *                         NOT comparable across providers; normalized for
 *                         logging/benchmark only
 * @param validationStatus copied from the source when the source exposes it
 *                         (SUGGESTED / VALIDATED / ...); null when the provider
 *                         does not carry it — central boundary enforcement never
 *                         depends on this field (invariant 1 is applied once,
 *                         outside providers)
 * @param embeddingModel   embedding model for vector candidates; null for
 *                         lexical/FS text arms
 * @param metadata         projection of canonical metadata (FS ≤ 20 fields);
 *                         e.g. chunk_index / page_start / page_end / document_kind
 */
public record RetrievalCandidate(
        String providerId,
        UUID documentRowId,
        String documentId,
        Integer docVersion,
        String evidenceLocator,
        UUID knowledgeNodeId,
        String nodeCode,
        String content,
        double providerScore,
        String validationStatus,
        String embeddingModel,
        Map<String, String> metadata) {

    public RetrievalCandidate {
        java.util.Objects.requireNonNull(providerId, "providerId");
        java.util.Objects.requireNonNull(evidenceLocator, "evidenceLocator");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

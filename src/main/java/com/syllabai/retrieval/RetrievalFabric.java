package com.syllabai.retrieval;

import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.ReciprocalRankFusion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * The retrieval fabric orchestrator (T-C13/T-C14) — the registered "port + 4
 * adapters, ZERO consumers" gap, now closed as an explicit, injectable
 * composition point. It orchestrates one query across N candidate-generation
 * arms ({@link RetrievalProvider}), applies the serving boundary ONCE
 * centrally ({@link BoundaryPolicy}, contract sketch invariant 1), and fuses
 * the per-arm rankings with the shipped {@link ReciprocalRankFusion} (k=60,
 * rank-only, score-free) — no new fusion code, the same deterministic fuser
 * the KA-RAG serving path already runs.
 *
 * <p><strong>Explicit composition, never injection-implied</strong> (the
 * run-003-b E-1 forward note): this class is deliberately NOT a Spring bean
 * and takes the provider list as an explicit constructor argument — a
 * provider-collecting {@code List<RetrievalProvider>} injection would pick up
 * every {@code @Component} adapter automatically (including the
 * {@code available() == false} File Search stub), making arm composition an
 * accident of classpath scanning. Arm promotion must stay explicit: whoever
 * wires serving constructs the fabric with the exact arms the T-C13 benchmark
 * promoted, nothing else. Until that promotion lands, nothing consumes this
 * class and no serving behavior changes.</p>
 *
 * <p><strong>Orchestration contract:</strong></p>
 * <ol>
 *   <li>health: a provider reporting {@code available() == false} is skipped
 *       entirely (honest unavailability, never polled — port contract);</li>
 *   <li>generation: each remaining provider returns candidates best-first
 *       (providers degrade honestly to empty lists per the port contract; a
 *       {@code null} return is a contract violation and fails closed);</li>
 *   <li>boundary: the {@link BoundaryPolicy} excludes non-servable candidates
 *       PRE-fusion, once, for every leg uniformly — each arm therefore ranks
 *       within its servable candidates (the post-fusion alternative burns
 *       top-k slots on unservable hits, the compliant-starved shape run-004-a
 *       recorded);</li>
 *   <li>fusion: the shipped {@link ReciprocalRankFusion} ranks-only across the
 *       per-arm lists (agreement rewarded, provider scores never read);
 *       candidates from different arms sharing an identity deduplicate to one
 *       fused entry;</li>
 *   <li>output: fused candidates best-first with {@code fusedScore} and the
 *       contributing arms attributed. The fabric does NOT truncate — the
 *       caller takes the top-k it needs (per-arm bounds live on the query).</li>
 * </ol>
 *
 * <p><strong>Identity:</strong> the fabric deduplicates on the candidate's
 * stable locator ({@code document_chunks.id} UUID string for chunk-backed
 * candidates, {@code knowledge_nodes.id} for KG candidates — the
 * {@code RetrievalCandidate} convention). A chunk candidate whose locator is
 * not a UUID is an identity-convention breach and fails closed.</p>
 *
 * <p><strong>Determinism:</strong> provider order is the constructor order;
 * the fusion tiebreaks deterministically (fused score, then source, then
 * stable key — inside the shipped fuser); no clocks, no randomness. Identical
 * inputs always fuse identically (§19 reproducibility).</p>
 */
public final class RetrievalFabric {

    /**
     * Plan §7 per-kind RRF weights (the v2 routing stance): knowledge-layer
     * evidence leads, question evidence is verbatim context, mark schemes only
     * surface when policy allows, cards are identity pointers. Sources absent
     * from this map (KG nodes, learner work, OTHER) weigh 1.0. These are the
     * weights the P3 routing posture ships with — any change re-runs the eval
     * harness first (plan §9: no retrieval change ships without it).
     *
     * <p>Canonical home is {@link ReciprocalRankFusion#PLAN_V2_WEIGHTS} (the
     * serving fusion consumes the same map — one source of truth, no drift);
     * this alias preserves the fabric's public API.</p>
     */
    public static final Map<EvidenceItem.EvidenceSource, Double> PLAN_V2_WEIGHTS =
            ReciprocalRankFusion.PLAN_V2_WEIGHTS;

    private final List<RetrievalProvider> providers;
    private final ReciprocalRankFusion fusion;
    private final BoundaryPolicy boundary;
    private final ToDoubleFunction<EvidenceItem> weightOf;

    /**
     * @param providers the explicit candidate-generation arms, in composition
     *                  order (defensively copied; never null elements)
     * @param fusion    the shipped rank-only fuser (k pinned by its own config)
     * @param boundary  the single central serving-boundary policy applied to
     *                  every candidate of every arm, pre-fusion
     */
    public RetrievalFabric(List<RetrievalProvider> providers,
                           ReciprocalRankFusion fusion,
                           BoundaryPolicy boundary) {
        this(providers, fusion, boundary, null);
    }

    /**
     * Weighted composition (plan §7 per-kind weights). {@code sourceWeights}
     * maps an evidence source to its RRF contribution weight; sources absent
     * from the map weigh 1.0 (the unweighted posture — the 3-arg constructor
     * is exactly this with a null map, so bench replays stay bit-identical).
     * Negative weights are a composition error and fail closed.
     */
    public RetrievalFabric(List<RetrievalProvider> providers,
                           ReciprocalRankFusion fusion,
                           BoundaryPolicy boundary,
                           Map<EvidenceItem.EvidenceSource, Double> sourceWeights) {
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(fusion, "fusion");
        Objects.requireNonNull(boundary, "boundary");
        providers.forEach(p -> Objects.requireNonNull(p, "provider element"));
        this.providers = List.copyOf(providers);
        this.fusion = fusion;
        this.boundary = boundary;
        if (sourceWeights == null) {
            this.weightOf = item -> 1.0;
        } else {
            Map<EvidenceItem.EvidenceSource, Double> copy = Map.copyOf(sourceWeights);
            copy.forEach((source, weight) -> {
                if (weight == null || weight < 0) {
                    throw new IllegalArgumentException(
                            "source weight for " + source + " must be non-negative");
                }
            });
            this.weightOf = item -> copy.getOrDefault(item.source(), 1.0);
        }
    }

    /** One fused retrieval candidate: identity + fused rank score + contributing arms. */
    public record FusedCandidate(RetrievalCandidate candidate, double fusedScore,
                                 List<String> providers) {
    }

    /**
     * Orchestrates the query across all arms and returns the fused ranking,
     * best-first (empty when no arm yields an eligible candidate — honest
     * emptiness, never null).
     */
    public List<FusedCandidate> retrieve(StructuredRetrievalQuery query) {
        Objects.requireNonNull(query, "query");

        Map<String, RetrievalCandidate> byLocator = new LinkedHashMap<>();
        Map<String, Set<String>> providersByLocator = new LinkedHashMap<>();
        List<List<EvidenceItem>> rankedLists = new ArrayList<>();

        for (RetrievalProvider provider : providers) {
            if (!provider.available()) {
                continue;   // honest unavailability — skipped, never polled
            }
            List<RetrievalCandidate> candidates = provider.retrieve(query);
            if (candidates == null) {
                throw new IllegalStateException("provider " + provider.id()
                        + " returned null candidates — contract violation (fail-closed)");
            }
            List<EvidenceItem> eligible = new ArrayList<>(candidates.size());
            for (RetrievalCandidate candidate : candidates) {
                if (!boundary.servingEligible(candidate)) {
                    continue;   // the ONE central exclusion, applied pre-fusion
                }
                // composition-order priority: the FIRST arm to emit an identity
                // is its primary source (putIfAbsent — later arms contribute
                // rank agreement, never overwrite the primary candidate)
                byLocator.putIfAbsent(locator(candidate), candidate);
                providersByLocator.computeIfAbsent(locator(candidate),
                        k -> new LinkedHashSet<>()).add(provider.id());
                eligible.add(toEvidence(candidate));
            }
            rankedLists.add(eligible);
        }

        List<EvidenceItem> fused = fusion.fuse(rankedLists, weightOf);
        List<FusedCandidate> out = new ArrayList<>(fused.size());
        for (EvidenceItem item : fused) {
            String locator = item.nodeId() != null ? item.nodeId().toString()
                    : String.valueOf(item.chunkId());
            RetrievalCandidate candidate = byLocator.get(locator);
            if (candidate == null) {
                throw new IllegalStateException("fused item without a tracked candidate: "
                        + locator + " (fail-closed)");
            }
            out.add(new FusedCandidate(candidate, item.fusedScore(),
                    List.copyOf(providersByLocator.get(locator))));
        }
        return List.copyOf(out);
    }

    /** Convenience view: the fused candidates only, best-first. */
    public List<RetrievalCandidate> retrieveCandidates(StructuredRetrievalQuery query) {
        return retrieve(query).stream().map(FusedCandidate::candidate).toList();
    }

    /** The stable identity the fabric deduplicates and fuses on. */
    private static String locator(RetrievalCandidate candidate) {
        return candidate.evidenceLocator();
    }

    /**
     * Fabric-port projection into the fuser's evidence shape (the exact inverse
     * of what the adapters lift). Chunk candidates keep every provenance field
     * the candidate carries; KG candidates map node identity across. Scores stay
     * native (retrievalScore) — the fuser is rank-only and never reads them.
     */
    private static EvidenceItem toEvidence(RetrievalCandidate candidate) {
        if (candidate.knowledgeNodeId() != null) {
            return new EvidenceItem(EvidenceItem.EvidenceSource.KNOWLEDGE_NODE,
                    candidate.content(), null, null, null, null, null,
                    candidate.knowledgeNodeId(), candidate.nodeCode(),
                    candidate.metadata().get("node_type"), candidate.metadata().get("node_title"),
                    null, null, List.of(),
                    List.of(candidate.knowledgeNodeId()),
                    candidate.providerScore(), 0.0, null);
        }
        UUID chunkId = parseChunkLocator(candidate);
        return new EvidenceItem(chunkSource(candidate), candidate.content(),
                candidate.documentRowId(), candidate.documentId(), candidate.docVersion(),
                chunkId, chunkIndex(candidate), null, null, null, null,
                integer(candidate, "page_start"), integer(candidate, "page_end"),
                elementIds(candidate), List.of(),
                candidate.providerScore(), 0.0, null);
    }

    /** Identity convention: chunk candidates locate by their document_chunks.id UUID. */
    private static UUID parseChunkLocator(RetrievalCandidate candidate) {
        try {
            return UUID.fromString(candidate.evidenceLocator());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("candidate locator is not a UUID: "
                    + candidate.providerId() + ":" + candidate.evidenceLocator()
                    + " (identity-convention breach, fail-closed)");
        }
    }

    private static EvidenceItem.EvidenceSource chunkSource(RetrievalCandidate candidate) {
        String kind = candidate.metadata().getOrDefault("document_kind", "OTHER");
        return switch (kind) {
            case "MARK_SCHEME" -> EvidenceItem.EvidenceSource.MARK_SCHEME;
            case "QUESTION_PAPER" -> EvidenceItem.EvidenceSource.QUESTION_PAPER;
            case "SYLLABUS" -> EvidenceItem.EvidenceSource.SYLLABUS;
            case "EXTERNAL_NOTES" -> EvidenceItem.EvidenceSource.NOTE;
            case "TEXTBOOK" -> EvidenceItem.EvidenceSource.TEXTBOOK;
            case "EXTERNAL_QUESTIONS" -> EvidenceItem.EvidenceSource.CARD;
            default -> EvidenceItem.EvidenceSource.OTHER;
        };
    }

    private static Integer chunkIndex(RetrievalCandidate candidate) {
        return integer(candidate, "chunk_index");
    }

    private static Integer integer(RetrievalCandidate candidate, String key) {
        String raw = candidate.metadata().get(key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("candidate metadata " + key + " is not an integer: "
                    + raw + " (fail-closed)");
        }
    }

    private static List<String> elementIds(RetrievalCandidate candidate) {
        String raw = candidate.metadata().get("element_ids");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(","));
    }
}

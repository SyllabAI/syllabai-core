package com.syllabai.tutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic candidate fusion (Master Spec §13 "Evidence fusion", T-024):
 * reciprocal rank fusion over the KG and vector candidate rankings. RRF is
 * deliberately score-free — cosine similarities and KG match scores live on
 * different scales, so fusing raw numbers would silently privilege one
 * source; ranks are the only honest common currency. Ties break
 * deterministically (score, then source, then stable id) so identical inputs
 * always fuse identically (§19 reproducibility).
 */
@Component
public class ReciprocalRankFusion {

    private final int k;

    /**
     * @param k RRF smoothing constant (standard 60); larger k flattens rank
     *          differences between sources
     */
    public ReciprocalRankFusion(@Value("${syllabai.tutor.rrf-k:60}") int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("rrf-k must be positive");
        }
        this.k = k;
    }

    /**
     * Plan §7 per-kind RRF weights — the canonical home (RetrievalFabric
     * aliases this map). The v2 routing stance: knowledge-layer evidence
     * leads, question evidence is verbatim context, mark schemes surface only
     * when policy allows ({@code ClaLeakagePolicy} governs the answer
     * surface), cards are identity pointers. Sources absent from this map
     * (KNOWLEDGE_NODE, LEARNER_WORK, OTHER) weigh 1.0 — KG topic anchors keep
     * their full pre-weights rank influence, so enabling the serving posture
     * only rescales chunk-kind influence. Plan §9: no retrieval change ships
     * without the eval harness — unit gates pin the induced ordering; the
     * T-C13 bench re-runs on the next corpus change. Rollback posture: the
     * unweighted 1-arg {@link #fuse(List)} stays bit-identical (bench replays,
     * offline evals); serving reverts by restoring the call sites.
     */
    public static final Map<EvidenceItem.EvidenceSource, Double> PLAN_V2_WEIGHTS = Map.of(
            EvidenceItem.EvidenceSource.NOTE, 1.0,
            EvidenceItem.EvidenceSource.SYLLABUS, 0.9,
            EvidenceItem.EvidenceSource.QUESTION_PAPER, 0.8,
            EvidenceItem.EvidenceSource.TEXTBOOK, 0.7,
            EvidenceItem.EvidenceSource.MARK_SCHEME, 0.6,
            EvidenceItem.EvidenceSource.CARD, 0.3);

    /**
     * @param rankedLists candidate rankings, best-first; a candidate appearing in
     *                    several lists accumulates score (agreement is rewarded)
     * @return fused ranking best-first with {@code fusedScore} set
     */
    public List<EvidenceItem> fuse(List<List<EvidenceItem>> rankedLists) {
        return fuse(rankedLists, item -> 1.0);
    }

    /**
     * Per-candidate weight function over {@link #PLAN_V2_WEIGHTS}; sources
     * absent from the map weigh 1.0 (never negative, never dropped).
     */
    public static java.util.function.ToDoubleFunction<EvidenceItem> planWeights() {
        return item -> PLAN_V2_WEIGHTS.getOrDefault(item.source(), 1.0);
    }

    /**
     * Serving fusion posture (plan §7): weighted fuse with the canonical plan
     * weights — exactly {@code fuse(rankedLists, planWeights())}. Rank order
     * inside each list is untouched; only cross-source influence scales.
     *
     * @param rankedLists candidate rankings, best-first
     * @return fused ranking best-first with {@code fusedScore} set
     */
    public List<EvidenceItem> fuseWithPlanWeights(List<List<EvidenceItem>> rankedLists) {
        return fuse(rankedLists, planWeights());
    }

    /**
     * Weighted RRF (plan §7 per-kind weights): each rank contribution is scaled
     * by the weight of the candidate's {@link EvidenceSource} — NOTE ≈ 1.0,
     * SPEC(SYLLABUS) ≈ 0.9, QUESTION_PAPER ≈ 0.8, TEXTBOOK ≈ 0.7,
     * MARK_SCHEME ≈ 0.6, CARDS ≈ 0.3. Rank order inside a list is untouched;
     * only the list's influence on the fused score is scaled. The unweighted
     * overload is exactly {@code weight 1.0 for every source}, so existing
     * fused rankings (bench replays, KA-RAG serving) are bit-identical.
     *
     * @param rankedLists candidate rankings, best-first
     * @param weightOf    per-candidate weight (non-negative; 0 removes the
     *                    source's influence without dropping the candidate)
     * @return fused ranking best-first with {@code fusedScore} set
     */
    public List<EvidenceItem> fuse(List<List<EvidenceItem>> rankedLists,
                                   java.util.function.ToDoubleFunction<EvidenceItem> weightOf) {
        Map<UUID, EvidenceItem> byKey = new LinkedHashMap<>();
        Map<UUID, Double> scores = new LinkedHashMap<>();

        for (List<EvidenceItem> ranking : rankedLists) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                EvidenceItem candidate = ranking.get(rank);
                UUID key = candidate.nodeId() != null ? candidate.nodeId() : candidate.chunkId();
                if (key == null) {
                    continue;   // evidence without grounding cannot be fused
                }
                double contribution = weightOf.applyAsDouble(candidate) / (k + rank + 1);
                scores.merge(key, contribution, Double::sum);
                byKey.put(key, candidate);
            }
        }

        List<EvidenceItem> fused = new ArrayList<>(byKey.size());
        for (Map.Entry<UUID, Double> entry : scores.entrySet()) {
            EvidenceItem item = byKey.get(entry.getKey());
            fused.add(item.withFusedScore(entry.getValue()));
        }
        fused.sort(Comparator
                .comparingDouble(EvidenceItem::fusedScore).reversed()
                .thenComparingInt(item -> item.source().ordinal())
                .thenComparing(item -> stableKey(item)));
        return fused;
    }

    private static String stableKey(EvidenceItem item) {
        return item.nodeCode() != null ? item.nodeCode()
                : (item.documentId() != null
                        ? item.documentId() + "#" + item.chunkIndex()
                        : String.valueOf(item.chunkId()));
    }
}

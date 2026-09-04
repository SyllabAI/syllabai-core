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
     * @param rankedLists candidate rankings, best-first; a candidate appearing in
     *                    several lists accumulates score (agreement is rewarded)
     * @return fused ranking best-first with {@code fusedScore} set
     */
    public List<EvidenceItem> fuse(List<List<EvidenceItem>> rankedLists) {
        Map<UUID, EvidenceItem> byKey = new LinkedHashMap<>();
        Map<UUID, Double> scores = new LinkedHashMap<>();

        for (List<EvidenceItem> ranking : rankedLists) {
            for (int rank = 0; rank < ranking.size(); rank++) {
                EvidenceItem candidate = ranking.get(rank);
                UUID key = candidate.nodeId() != null ? candidate.nodeId() : candidate.chunkId();
                if (key == null) {
                    continue;   // evidence without grounding cannot be fused
                }
                double contribution = 1.0 / (k + rank + 1);
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

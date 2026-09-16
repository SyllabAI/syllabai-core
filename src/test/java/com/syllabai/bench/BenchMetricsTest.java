package com.syllabai.bench;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-computed cases for the T-C13 metrics engine — the formulas must stay
 * line-for-line identical to the Run-001 B-proxy scorer (bench/bproxy_score.py),
 * otherwise cross-run comparison breaks silently. These tests pin that contract.
 */
class BenchMetricsTest {

    private static final double EPS = 1e-9;

    @Test
    void chunkMetricsMatchRun001Formulas() {
        Map<String, Integer> tiers = new LinkedHashMap<>();
        tiers.put("A", 2);
        tiers.put("B", 1);
        tiers.put("C", 0);
        tiers.put("D", 1);
        List<String> ranked = List.of("C", "A", "B", "D", "E", "F", "G", "H", "I", "J");
        BenchMetrics.ChunkRow row = BenchMetrics.scoreChunks(ranked, tiers);

        assertEquals(1.0, row.recallAt5(), EPS);          // {A,B,D} all in top-5
        assertEquals(1.0, row.recallAt10(), EPS);
        assertEquals(1.0, row.recallAt20(), EPS);
        assertEquals(0.5, row.mrr(), EPS);                // first tier-2 (A) at rank 2
        // dcg = 3/log2(3) [A@pos2] + 1/log2(4) [B@pos3] + 1/log2(5) [D@pos4]
        // idcg = 3/log2(2) + 1/log2(3) + 1/log2(4)
        double dcg = 3.0 / (Math.log(3) / Math.log(2)) + 0.5 + 1.0 / (Math.log(5) / Math.log(2));
        double idcg = 3.0 + 1.0 / (Math.log(3) / Math.log(2)) + 0.5;
        assertEquals(dcg / idcg, row.ndcgAt10(), 1e-6);
        assertEquals(0.3, row.precisionAt10(), EPS);      // 3 relevant / fixed 10
        assertEquals(0.7, row.falsePositiveAt10(), EPS);  // 7 tier-0 / fixed 10
    }

    @Test
    void emptyCandidateListScoresRealZeros() {
        Map<String, Integer> tiers = Map.of("A", 2, "B", 1);
        BenchMetrics.ChunkRow row = BenchMetrics.scoreChunks(List.of(), tiers);
        assertEquals(0.0, row.recallAt5(), EPS);
        assertEquals(0.0, row.recallAt10(), EPS);
        assertEquals(0.0, row.recallAt20(), EPS);
        assertEquals(0.0, row.mrr(), EPS);
        assertEquals(0.0, row.ndcgAt10(), EPS);
        assertEquals(0.0, row.precisionAt10(), EPS);
        assertEquals(0.0, row.falsePositiveAt10(), EPS);
    }

    @Test
    void mrrOnlyCountsTierTwoWithinTop20() {
        Map<String, Integer> tiers = new LinkedHashMap<>();
        for (int i = 1; i <= 25; i++) {
            tiers.put("C" + i, i == 21 ? 2 : 1);
        }
        List<String> ranked = tiers.keySet().stream().toList();
        BenchMetrics.ChunkRow row = BenchMetrics.scoreChunks(ranked, tiers);
        assertEquals(0.0, row.mrr(), EPS);   // tier-2 sits at rank 21 — outside the top-20 window
    }

    @Test
    void resolutionCoverageAndPrecision() {
        BenchMetrics.ResolutionRow partial =
                BenchMetrics.scoreResolution(List.of("X", "Y"), List.of("X", "Z"));
        assertEquals(0.5, partial.coverage(), EPS);
        assertFalse(partial.exactHit());
        assertEquals(0.5, partial.precision(), EPS);

        BenchMetrics.ResolutionRow exact =
                BenchMetrics.scoreResolution(List.of("X", "Z", "Q"), List.of("X", "Z"));
        assertEquals(1.0, exact.coverage(), EPS);
        assertTrue(exact.exactHit());

        BenchMetrics.ResolutionRow none =
                BenchMetrics.scoreResolution(List.of(), List.of("X"));
        assertEquals(0.0, none.coverage(), EPS);
        assertFalse(none.exactHit());
        assertNull(none.precision());   // undefined, never silently averaged as zero
    }

    @Test
    void aggregationRoundsToFourDecimals() {
        List<BenchMetrics.ResolutionRow> rows = List.of(
                BenchMetrics.scoreResolution(List.of("X"), List.of("X", "Y")),
                BenchMetrics.scoreResolution(List.of("X", "Y"), List.of("X", "Y")));
        Map<String, Object> agg = BenchMetrics.aggregateResolution(rows);
        assertEquals(0.75, (Double) agg.get("coverage_mean"), EPS);
        assertEquals(0.5, (Double) agg.get("exact_hit_rate"), EPS);
        assertEquals(1.0, (Double) agg.get("matched_ratio"), EPS);
    }
}

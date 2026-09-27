package com.syllabai.bench;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * §8(d) SpecificationPoint resolution over the chunk→SP HUMAN_VALIDATED
 * projection (harness spec §5 flagship metric, chunk-evidence arms).
 *
 * <p>Definition (spec §5, verbatim): "does the arm surface evidence whose
 * {@code HUMAN_VALIDATED} spec mapping covers the query's gold spec points".
 * The covered code set is the union of {@link BenchSnapshot#hvSpecCodesByChunkRef()}
 * over the arm's served (ranked) evidence refs; AI_SUGGESTED / RULE_DERIVED
 * mappings never count — the loader enforces that only operator-promoted
 * HUMAN_VALIDATED rows exist in the artifact at all.</p>
 *
 * <p>Coverage granularity is NOT pinned by a §10 ruling and the recorded
 * generations scored §8(d) NOT SCOREABLE (no precedent exists), so this
 * scorer reports BOTH readings of "covers" on every run:
 * <ul>
 *   <li>{@code full_coverage_rate} — per-query binary hit iff the covered set
 *       contains ALL of the query's gold points (the stricter reading, the
 *       recommended gate input until ruled otherwise);</li>
 *   <li>{@code micro_average} — per-point micro-mean over every gold point
 *       (total covered / total gold across scored queries).</li>
 * </ul>
 * They differ only on multi-spec-point queries (gold class 11). Gate
 * arithmetic on full coverage; the run report must state the rule.</p>
 *
 * <p>Dual-denominator context (§10 ruling 1) is inherited by the callers: the
 * projection's chunk_refs all belong to EXTERNAL_NOTES chunks whose content
 * validation_state is SUGGESTED (the HV status is on the mapping, not the
 * chunk content), so on a VALIDATED-only serving view the covered set
 * collapses — gate arithmetic stays on the ALL denominator, with this scorer's
 * census reported alongside.</p>
 *
 * <p>Pure and deterministic, same discipline as {@link BenchMetrics}: no
 * clocks, no I/O, micro-mean aggregation rounded to 4 decimal places.</p>
 */
public final class ChunkSpecHvResolution {

    private ChunkSpecHvResolution() {
    }

    /** One query's §8(d) row. */
    public record QueryResolution(String queryId, int goldN, int coveredN,
                                  boolean fullCoverage, double perPointCoverage) {
    }

    /**
     * Scores one query. Empty served refs (honest zero-result arms) score as
     * real zeros, consistent with the chunk-axis convention — never excluded.
     */
    public static QueryResolution scoreQuery(String queryId, List<String> rankedRefs,
                                             List<String> goldPoints,
                                             Map<String, Set<String>> hvCodesByRef) {
        Set<String> gold = new LinkedHashSet<>(goldPoints);
        Set<String> covered = new HashSet<>();
        for (String ref : rankedRefs) {
            covered.addAll(hvCodesByRef.getOrDefault(ref, Set.of()));
        }
        int hits = 0;
        for (String g : gold) {
            if (covered.contains(g)) {
                hits++;
            }
        }
        boolean full = !gold.isEmpty() && hits == gold.size();
        double perPoint = gold.isEmpty() ? 0.0 : (double) hits / gold.size();
        return new QueryResolution(queryId, gold.size(), hits, full, perPoint);
    }

    /** Micro-mean aggregates over query rows (run-001 convention, r4 rounding). */
    public static Map<String, Object> aggregate(List<QueryResolution> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        long goldTotal = 0;
        long coveredTotal = 0;
        long fullHits = 0;
        for (QueryResolution r : rows) {
            goldTotal += r.goldN();
            coveredTotal += r.coveredN();
            if (r.fullCoverage()) {
                fullHits++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spec_points_full_coverage_rate", r4(rows.size() == 0 ? 0.0 : fullHits / (double) rows.size()));
        out.put("spec_points_micro_average",
                r4(goldTotal == 0 ? 0.0 : coveredTotal / (double) goldTotal));
        out.put("queries_scored", rows.size());
        out.put("gold_points_total", goldTotal);
        out.put("gold_points_covered", coveredTotal);
        return out;
    }

    /**
     * Gold spec points (across the scored queries) that appear in NO
     * HUMAN_VALIDATED mapping row of the projection — the structurally
     * unbridged codes. They surface as explicit misses, never coerced into
     * coverage (bridge contract: the 4CH1-1.52C page-chrome MISS and the
     * 4CH1-4.15 worklist gap stay honest).
     */
    public static List<String> unbridgedGoldPoints(List<String> goldPoints,
                                                   Map<String, Set<String>> hvCodesByRef) {
        Set<String> universe = new HashSet<>();
        hvCodesByRef.values().forEach(universe::addAll);
        List<String> out = new ArrayList<>();
        new LinkedHashSet<>(goldPoints).stream().sorted().forEach(g -> {
            if (!universe.contains(g) && !out.contains(g)) {
                out.add(g);
            }
        });
        return out;
    }

    private static double r4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

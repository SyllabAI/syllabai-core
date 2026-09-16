package com.syllabai.bench;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * T-C13 harness (spec §5): pure, deterministic scoring. No clocks, no I/O.
 *
 * <p>Chunk-axis formulas are an exact port of the Run-001 B-proxy scorer
 * ({@code bench/bproxy_score.py}) so A0/B-proxy/future-arm numbers are
 * comparable line-for-line: Recall@k over the tier≥1 gold set, MRR over the
 * first tier-2 hit within the top-20, nDCG@10 with gain 2^tier−1 and the
 * ideal ranking taken from the candidate list itself, precision/FP over the
 * fixed top-10 denominator. Aggregation is the same micro-mean over query
 * rows, rounded to 4 decimal places.</p>
 *
 * <p>SpecPoint resolution (spec §5.1, the flagship axis) is defined here for
 * entity-emitting arms: coverage = fraction of the query's gold spec points
 * present in the arm's ranked topics; exact hit = coverage 1.0; precision =
 |matched ∩ gold| / |matched| over queries with ≥1 match (reported with the
 * matched ratio, never silently averaged over empty-match queries).</p>
 */
public final class BenchMetrics {

    private BenchMetrics() {
    }

    /** Chunk-axis row, same fields as run-001 results.json. */
    public record ChunkRow(double recallAt5, double recallAt10, double recallAt20,
                           double mrr, double ndcgAt10, double precisionAt10,
                           double falsePositiveAt10) {
    }

    public record ResolutionRow(double coverage, boolean exactHit, Double precision,
                                int matched, int goldN) {
    }

    public static ChunkRow scoreChunks(List<String> rankedRefs, Map<String, Integer> tierByRef) {
        Set<String> gold = new HashSet<>();
        Set<String> gold2 = new HashSet<>();
        tierByRef.forEach((ref, tier) -> {
            if (tier >= 1) {
                gold.add(ref);
            }
            if (tier == 2) {
                gold2.add(ref);
            }
        });
        List<String> ranked = rankedRefs;
        double r5 = recall(ranked, gold, 5);
        double r10 = recall(ranked, gold, 10);
        double r20 = recall(ranked, gold, 20);
        double mrr = 0.0;
        for (int n = 0; n < ranked.size() && n < 20; n++) {
            if (gold2.contains(ranked.get(n))) {
                mrr = 1.0 / (n + 1);
                break;
            }
        }
        double dcg = 0.0;
        for (int n = 0; n < ranked.size() && n < 10; n++) {
            int tier = tierByRef.getOrDefault(ranked.get(n), 0);
            dcg += (Math.pow(2, tier) - 1) / log2(n + 2);
        }
        List<Integer> ideal = new ArrayList<>();
        for (String ref : ranked) {
            ideal.add(tierByRef.getOrDefault(ref, 0));
        }
        ideal.sort((a, b) -> Integer.compare(b, a));
        double idcg = 0.0;
        for (int n = 0; n < ideal.size() && n < 10; n++) {
            idcg += (Math.pow(2, ideal.get(n)) - 1) / log2(n + 2);
        }
        double ndcg = idcg == 0.0 ? 0.0 : dcg / idcg;
        double hits = 0.0;
        for (int n = 0; n < ranked.size() && n < 10; n++) {
            if (tierByRef.getOrDefault(ranked.get(n), 0) >= 1) {
                hits += 1.0;
            }
        }
        // fixed /10 denominator exactly like run-001 (top-10 is always full there;
        // for sparse candidate lists the fixed denominator keeps the same scale)
        double precision = hits / 10.0;
        double fp = 0.0;
        for (int n = 0; n < ranked.size() && n < 10; n++) {
            if (tierByRef.getOrDefault(ranked.get(n), 0) == 0) {
                fp += 1.0;
            }
        }
        fp = fp / 10.0;
        return new ChunkRow(r5, r10, r20, mrr, ndcg, precision, fp);
    }

    private static double recall(List<String> ranked, Set<String> gold, int k) {
        if (gold.isEmpty()) {
            return 0.0;
        }
        Set<String> top = new HashSet<>();
        for (int n = 0; n < ranked.size() && n < k; n++) {
            top.add(ranked.get(n));
        }
        top.retainAll(gold);
        return (double) top.size() / gold.size();
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    public static ResolutionRow scoreResolution(List<String> rankedCodes, List<String> goldCodes) {
        Set<String> gold = new HashSet<>(goldCodes);
        Set<String> matched = new HashSet<>();
        for (String code : rankedCodes) {
            if (gold.contains(code)) {
                matched.add(code);
            }
        }
        double coverage = gold.isEmpty() ? 0.0 : (double) matched.size() / gold.size();
        Double precision = matched.isEmpty() ? null : (double) matched.size() / rankedCodes.size();
        return new ResolutionRow(coverage, matched.size() == gold.size() && !gold.isEmpty(),
                precision, matched.size(), gold.size());
    }

    /** Micro-mean over rows (run-001 convention), values rounded to 4 dp. */
    public static Map<String, Object> aggregateChunks(List<ChunkRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recall@5", r4(mean(rows, ChunkRow::recallAt5)));
        out.put("recall@10", r4(mean(rows, ChunkRow::recallAt10)));
        out.put("recall@20", r4(mean(rows, ChunkRow::recallAt20)));
        out.put("mrr", r4(mean(rows, ChunkRow::mrr)));
        out.put("ndcg@10", r4(mean(rows, ChunkRow::ndcgAt10)));
        out.put("evidence_precision@10", r4(mean(rows, ChunkRow::precisionAt10)));
        out.put("false_positive_rate@10", r4(mean(rows, ChunkRow::falsePositiveAt10)));
        return out;
    }

    public static Map<String, Object> aggregateResolution(List<ResolutionRow> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Double> precisions = rows.stream().map(ResolutionRow::precision)
                .filter(p -> p != null).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coverage_mean", r4(rows.stream().mapToDouble(ResolutionRow::coverage).average().orElse(0)));
        out.put("exact_hit_rate", r4(rows.stream().filter(ResolutionRow::exactHit).count() / (double) rows.size()));
        out.put("matched_ratio", r4(rows.stream().filter(r -> r.matched() > 0).count() / (double) rows.size()));
        out.put("precision_mean_over_matched", r4(precisions.isEmpty() ? 0.0
                : precisions.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        out.put("gold_points_mean", r4(rows.stream().mapToDouble(ResolutionRow::goldN).average().orElse(0)));
        return out;
    }

    private static double mean(List<ChunkRow> rows, java.util.function.ToDoubleFunction<ChunkRow> f) {
        return rows.stream().mapToDouble(f).average().orElse(0);
    }

    private static double r4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Group rows by class key preserving class order (TreeMap = code order). */
    public static <T> Map<String, List<T>> groupByClass(Map<String, String> classById,
                                                        Map<String, T> rowById) {
        Map<String, List<T>> out = new TreeMap<>();
        rowById.forEach((id, row) ->
                out.computeIfAbsent(classById.get(id), k -> new ArrayList<>()).add(row));
        return out;
    }
}

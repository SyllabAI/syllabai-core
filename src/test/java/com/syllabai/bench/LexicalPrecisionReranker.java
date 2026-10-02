package com.syllabai.bench;

import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Arm D's reranker (T-C63 rank-quality lane) — deterministic query×content
 * lexical-precision rescoring of the fused pool, implementing the PRODUCTION
 * {@link EvidenceReranker} port (T-024). Bench-scope by design: the harness
 * measures (spec §9); if the §8.1 gate passes, promotion is a production
 * wiring decision in the owning lane, behind this same port.
 *
 * <p><strong>PRE-REGISTERED design</strong> (records repo
 * {@code evidence/bench-001/rank-quality-lane-2026-10-02/CHARTER.md}, recorded
 * BEFORE any arm-D run; spec §9 forbids tuning loops against the frozen set —
 * any parameter change requires a dated new pre-registration and a new run):</p>
 * <ul>
 *   <li>tokenizer: lowercase, split on non-alphanumeric runs (spec-code
 *       fragments like {@code 4ch1} / {@code 2} / {@code 5c} are ordinary
 *       tokens — NO spec-code special-casing, NO kind/source awareness, NO
 *       gold knowledge);</li>
 *   <li>IDF: per-query, computed over the rerank pool itself —
 *       {@code idf(t) = ln(1 + (N − df(t) + 0.5) / (df(t) + 0.5))}, N = pool
 *       size. Self-contained: no corpus-wide statistics, no snapshot-drift
 *       surface; boilerplate present in every candidate self-annihilates;</li>
 *   <li>score: {@code Σ_{t ∈ unique query terms} idf(t) · tf(t)·(k1+1)/(tf(t)+k1)}
 *       with {@code k1 = 1.2} (the BM25 default) and {@code b = 0} — NO length
 *       normalization, deliberately: the spec-mapped notes carriers are
 *       systematically longer than QP/MS chunks, and a length penalty would
 *       demote exactly the evidence §8(d) prices (declared in the charter);</li>
 *   <li>ordering: score descending, ties broken by incoming (fused) rank
 *       ascending — stable and deterministic; {@code rerankScore} set on every
 *       returned item; candidates are never invented or dropped (port
 *       contract: "only reorders/rescores what fusion produced").</li>
 * </ul>
 *
 * <p>Pure and deterministic (no clocks, no randomness, no I/O, no model
 * inference): identical (query, pool) inputs always rerank identically, so
 * the bench double-pass determinism contract holds by construction.</p>
 */
public final class LexicalPrecisionReranker implements EvidenceReranker {

    /** Pre-registered saturation constant (the BM25 default; charter). */
    static final double K1 = 1.2;

    public LexicalPrecisionReranker() {
    }

    /** The env-spec token Run005C accepts for this reranker (BENCH_ARM_D_RERANKER). */
    public static String envSpec() {
        return "lexical_precision";
    }

    @Override
    public List<EvidenceItem> rerank(String query, List<EvidenceItem> candidates) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates must not be null (port contract)");
        }
        Set<String> queryTerms = query == null ? Set.of() : tokenize(query);
        if (candidates.isEmpty() || queryTerms.isEmpty()) {
            // honest pass-through: nothing to score — the fused order stands,
            // rerankScore 0.0 stamped for contract completeness
            List<EvidenceItem> out = new ArrayList<>(candidates.size());
            for (EvidenceItem item : candidates) {
                out.add(item.withRerankScore(0.0));
            }
            return out;
        }

        int n = candidates.size();
        List<Map<String, Integer>> termFrequencies = new ArrayList<>(n);
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (EvidenceItem item : candidates) {
            Map<String, Integer> tf = termFrequencies(tokenize(item.content()));
            termFrequencies.add(tf);
            for (String term : tf.keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = termFrequencies.get(i);
            double score = 0.0;
            for (String term : queryTerms) {
                Integer freq = tf.get(term);
                if (freq == null || freq == 0) {
                    continue;
                }
                int df = documentFrequency.get(term);
                double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
                score += idf * (freq * (K1 + 1.0)) / (freq + K1);
            }
            scores[i] = score;
        }

        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort((a, b) -> {
            int byScore = Double.compare(scores[b], scores[a]);
            return byScore != 0 ? byScore : Integer.compare(a, b);
        });

        List<EvidenceItem> out = new ArrayList<>(n);
        for (int index : order) {
            out.add(candidates.get(index).withRerankScore(scores[index]));
        }
        return out;
    }

    /** Lowercase alphanumeric runs (the pre-registered tokenizer). */
    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> terms = new java.util.LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean alphanumeric = Character.isLetterOrDigit(c);
            if (alphanumeric) {
                current.append(Character.toLowerCase(c));
            } else if (current.length() > 0) {
                terms.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            terms.add(current.toString());
        }
        return terms;
    }

    private static Map<String, Integer> termFrequencies(Set<String> terms) {
        Map<String, Integer> tf = new HashMap<>();
        for (String term : terms) {
            tf.merge(term, 1, Integer::sum);
        }
        return tf;
    }
}

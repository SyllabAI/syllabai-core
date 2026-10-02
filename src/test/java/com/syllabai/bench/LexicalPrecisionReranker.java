package com.syllabai.bench;

import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * T-C63 arm D instrument (bench scope, tranche 1): deterministic query×content
 * rescoring of the fused pool, behind the production {@link EvidenceReranker}
 * port (T-024). This class is NOT a Spring bean and lives in test scope —
 * nothing in production constructs a reranking fabric (harness spec §9
 * guardrail; arm promotion happens in the owning lane after the owner accepts
 * a §8-passing verdict).
 *
 * <p><strong>The algorithm below is PRE-REGISTERED</strong> — frozen verbatim in
 * {@code evidence/bench-001/rank-quality-lane-2026-10-02/CHARTER.md} BEFORE any
 * recorded run (spec §9 forbids tuning loops against the frozen set; r3
 * anti-gaming doctrine). Changing any constant after seeing results is the
 * anti-gaming violation: it requires a dated new pre-registration and a new run.</p>
 *
 * <ul>
 *   <li><strong>Tokenizer:</strong> lowercase; letter-runs and digit-runs are
 *       tokens (spec-code fragments like {@code 4ch1}, {@code 2}, {@code 5c} are
 *       ordinary token material — NO spec-code special-casing, NO kind-awareness,
 *       NO gold knowledge).</li>
 *   <li><strong>IDF:</strong> per-query, over the rerank pool itself:
 *       {@code idf(t) = ln(1 + (N − df(t) + 0.5)/(df(t) + 0.5))}, N = pool size
 *       (self-contained ⇒ no corpus-statistics dependency ⇒ no snapshot drift;
 *       boilerplate present in every candidate self-annihilates).</li>
 *   <li><strong>Score:</strong> {@code Σ_{t ∈ unique query tokens} idf(t) ·
 *       tf(t)·(k1+1)/(tf(t)+k1)} with <strong>k1 = 1.2</strong> (the BM25
 *       default) and <strong>b = 0 (NO length normalization)</strong>. The b=0
 *       choice is deliberate and was declared in the charter: the spec-mapped
 *       notes carriers are systematically longer than QP/MS chunks; a length
 *       penalty would demote exactly the evidence §8(d) prices.</li>
 *   <li><strong>Ordering:</strong> score desc; ties broken by fused rank
 *       ascending (the port delivers candidates best-first, so a stable sort
 *       over the input order IS the fused-rank tiebreak — made explicit in the
 *       comparator). {@code rerankScore} is set on EVERY item, including
 *       zero-score ones. Never invents or drops candidates (port contract).</li>
 * </ul>
 */
final class LexicalPrecisionReranker implements EvidenceReranker {

    /** The one pre-registered name Run005C accepts for BENCH_ARM_D_RERANKER. */
    static final String NAME = "lexical_precision";

    /** The BM25 default — pre-registered, never tuned against the frozen set. */
    private static final double K1 = 1.2;

    LexicalPrecisionReranker() {
    }

    /**
     * The single fail-closed factory: blank specs never reach this method
     * (Run005C routes blank to the NoReranker byte-identity path); anything
     * else that is not the one pre-registered name is a composition error.
     */
    static EvidenceReranker forName(String spec) {
        if (NAME.equals(spec)) {
            return new LexicalPrecisionReranker();
        }
        throw new IllegalStateException("unknown reranker spec '" + spec
                + "' — supported: '" + NAME + "' (fail-closed: never silently run unreranked)");
    }

    @Override
    public List<EvidenceItem> rerank(String query, List<EvidenceItem> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        int n = candidates.size();
        if (n == 0) {
            return List.of();
        }

        // unique query tokens in first-occurrence order (deterministic iteration)
        Set<String> queryTokens = new LinkedHashSet<>(tokens(query == null ? "" : query));

        // tokenize the pool once — scores are a pure function of query × pool
        List<List<String>> poolTokens = new ArrayList<>(n);
        for (EvidenceItem item : candidates) {
            poolTokens.add(tokens(item.content() == null ? "" : item.content()));
        }

        double[] score = new double[n];
        for (String token : queryTokens) {
            int df = 0;
            for (List<String> toks : poolTokens) {
                if (toks.contains(token)) {
                    df++;
                }
            }
            double idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
            for (int i = 0; i < n; i++) {
                int tf = 0;
                for (String tok : poolTokens.get(i)) {
                    if (tok.equals(token)) {
                        tf++;
                    }
                }
                score[i] += idf * tf * (K1 + 1.0) / (tf + K1);
            }
        }

        // score desc; ties by fused rank ascending (input order = fused rank order,
        // the port contract's best-first precondition — made explicit, not relied
        // on implicitly through sort stability)
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int byScore = Double.compare(score[b], score[a]);
            return byScore != 0 ? byScore : Integer.compare(a, b);
        });

        List<EvidenceItem> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(candidates.get(order[i]).withRerankScore(score[order[i]]));
        }
        return List.copyOf(out);
    }

    /**
     * Pre-registered tokenizer: lowercase, then maximal letter-runs and
     * digit-runs are tokens — every other character is a separator. A mixed
     * alphanumeric run like {@code 4ch1} decomposes into {@code 4}, {@code ch},
     * {@code 1} (letter-runs and digit-runs, per the charter). No regex, no
     * locale-sensitive normalization beyond {@link Locale#ROOT} lowercasing —
     * deterministic byte-for-byte.
     */
    private static List<String> tokens(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        int i = 0;
        int len = lower.length();
        while (i < len) {
            char c = lower.charAt(i);
            if (!letterOrDigit(c)) {
                i++;
                continue;
            }
            boolean digit = Character.isDigit(c);
            int start = i;
            while (i < len) {
                char d = lower.charAt(i);
                if (!letterOrDigit(d) || Character.isDigit(d) != digit) {
                    break;
                }
                i++;
            }
            out.add(lower.substring(start, i));
        }
        return out;
    }

    private static boolean letterOrDigit(char c) {
        return Character.isLetter(c) || Character.isDigit(c);
    }
}

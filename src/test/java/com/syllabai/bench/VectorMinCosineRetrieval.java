package com.syllabai.bench;

import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * T-C83 (rank-quality lane tranche 8): the precision-side cosine trim — the
 * production vector arm's post-cut candidates filtered to a minimum cosine,
 * behind {@code BENCH_MIN_COSINE}, pre-registered in {@code
 * MIN-COSINE-PREREGISTRATION.md} (records 40a6724) BEFORE any run.
 *
 * <p><strong>Semantics (pinned by the pre-registration):</strong> per query,
 * AFTER the vector arm's recorded limit cut and AFTER the production
 * {@code ContentVectorRetriever#MIN_COSINE} 0.50 floor (both inside the
 * delegate), the delegate's candidates with {@code providerScore < theta}
 * are REMOVED — the candidate's own native cosine, the exact signal the
 * production constant filters on (ChunkVectorRepository scores
 * {@code 1 - (embedding <=> query)} = cosine similarity; the production
 * retriever applies {@code MIN_COSINE} as a POST-cut stream filter, and this
 * wrapper is that same filter shape at the bench posture). REMOVAL-ONLY: the
 * list under-fills rather than backfills (the pre-cut backfill form was
 * explicitly rejected — the T-C69 CAP-form ground: a pre-LIMIT predicate
 * conflates removal with admission). The bm25 arm is untouched (bm25 has no
 * cosine). Survivors keep their positions, so their fused RRF scores are
 * byte-identical to the untrimmed run and the shipped fusion's tie-break
 * ({@code fusedScore} DESC → source ordinal → stableKey) is
 * position-independent: the whole posture's delta is the removed-ref ledger.
 * The removal-only shape makes the run pool a per-query SUBSET of the
 * recorded depth-40 pool — the T-C67 superset theorem's inverted form:
 * §8(d) is monotone NON-INCREASING, by construction.</p>
 *
 * <p><strong>Honesty instruments:</strong> removals are tracked per stripped
 * query text (idempotent across the determinism double-pass — same query
 * texts) and recorded in the run's results ({@code min_cosine_removed}):
 * the affected queries and the removed total. The under-fill is the
 * removal — it is never patched by backfilling.</p>
 *
 * <p><strong>Absent-path identity:</strong> a θ &le; 0 returns the DELEGATE
 * ITSELF (never a wrapper) — the recorded path is byte-identical by
 * construction (the S8D absent-path precedent, same shape as
 * {@link NotesFloorRetrieval#maybeWrap}).</p>
 */
public final class VectorMinCosineRetrieval {

    private VectorMinCosineRetrieval() {
    }

    /**
     * Spec parser: absent/blank = 0 (the recorded posture, byte-identical);
     * a double in (0, 1] = the minimum-cosine trim; anything else fails
     * closed — never a silently-different posture.
     */
    static double minCosineForSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        String trimmed = spec.trim();
        double value;
        try {
            value = Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("unknown BENCH_MIN_COSINE spec: '" + spec
                    + "' (expected a double in (0, 1], e.g. 0.60) — fail-closed");
        }
        if (Double.isNaN(value) || value <= 0 || value > 1) {
            throw new IllegalStateException("BENCH_MIN_COSINE out of range: " + value
                    + " (expected a double in (0, 1]) — fail-closed");
        }
        return value;
    }

    /**
     * A θ &le; 0 returns the delegate ITSELF (absent-path byte-identity by
     * construction — never a wrapper).
     */
    public static RetrievalProvider maybeWrap(RetrievalProvider delegate, double minCosine) {
        if (minCosine <= 0) {
            return delegate;
        }
        return new MinCosineProvider(delegate, minCosine);
    }

    /** The trimmed provider: a pure post-cut filter over the delegate's output. */
    public static final class MinCosineProvider implements RetrievalProvider {

        private final RetrievalProvider delegate;
        private final double minCosine;
        // per stripped query text: the removal count, kept as the MAX across
        // retrieval calls — the served view, the compliant view and the
        // determinism double-pass all retrieve the same query texts, so the
        // ledger is idempotent (the kind-arm tracking precedent)
        private final Map<String, Integer> removedByQuery = new LinkedHashMap<>();

        MinCosineProvider(RetrievalProvider delegate, double minCosine) {
            this.delegate = Objects.requireNonNull(delegate, "delegate provider");
            if (minCosine <= 0 || minCosine > 1) {
                throw new IllegalArgumentException("minCosine must be in (0, 1], got " + minCosine);
            }
            this.minCosine = minCosine;
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public boolean available() {
            return delegate.available();
        }

        @Override
        public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
            List<RetrievalCandidate> base = delegate.retrieve(query);
            List<RetrievalCandidate> out = new ArrayList<>(base.size());
            int removed = 0;
            for (RetrievalCandidate c : base) {
                if (c.providerScore() >= minCosine) {
                    out.add(c);
                } else {
                    removed++;
                }
            }
            removedByQuery.merge(query.normalizedQuery(), removed, Integer::max);
            return out;
        }

        /** Distinct queries whose list lost at least one candidate to the trim. */
        public int affectedQueries() {
            return (int) removedByQuery.values().stream().filter(v -> v > 0).count();
        }

        /** Total candidates removed per the idempotent per-query ledger. */
        public long removedTotal() {
            return removedByQuery.values().stream().mapToInt(Integer::intValue).sum();
        }

        /** The pinned θ (for the results ledger). */
        public double minCosine() {
            return minCosine;
        }
    }
}

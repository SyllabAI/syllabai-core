package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The T-C83 precision-side seam: {@code BENCH_MIN_COSINE} trims the vector
 * arm's post-cut candidates to a minimum cosine (the production constant's
 * own filter shape, pre-registered in MIN-COSINE-PREREGISTRATION.md BEFORE
 * any run). Absence keeps the bare provider (byte-identical by construction),
 * unknown specs fail closed, the six levers (reranker × weights × floor ×
 * kind-arm-with-rank-bound × min-cosine) are mutually exclusive, and the
 * trim's removal-only semantics hold: below-θ candidates are removed, the
 * list under-fills and NEVER backfills, survivors keep their positions and
 * their native scores (the pure-filter property the pre-registration's
 * byte-exact predictions rest on), the boundary value is kept (>= θ), and
 * the removal ledger is idempotent across repeated retrieval of the same
 * query text (the determinism double-pass).
 */
class Run005CMinCosineTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.nameUUIDFromBytes("t-c83-min-cosine-test".getBytes()), "BENCH-TEST", Set.of());

    // ── spec parser ──────────────────────────────────────────────────────

    @Test
    @DisplayName("absent/blank spec -> 0 (the recorded posture, byte-identical)")
    void absentSpecIsZero() {
        assertThat(VectorMinCosineRetrieval.minCosineForSpec(null)).isZero();
        assertThat(VectorMinCosineRetrieval.minCosineForSpec("")).isZero();
        assertThat(VectorMinCosineRetrieval.minCosineForSpec("   ")).isZero();
    }

    @Test
    @DisplayName("double in (0, 1] parses (whitespace-tolerant)")
    void doubleSpecParses() {
        assertThat(VectorMinCosineRetrieval.minCosineForSpec("0.60")).isEqualTo(0.60);
        assertThat(VectorMinCosineRetrieval.minCosineForSpec(" 0.5 ")).isEqualTo(0.5);
        assertThat(VectorMinCosineRetrieval.minCosineForSpec("1")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("unknown/non-numeric/out-of-range spec fails closed — never silently different")
    void unknownSpecFailsClosed() {
        assertThatThrownBy(() -> VectorMinCosineRetrieval.minCosineForSpec("half"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BENCH_MIN_COSINE");
        assertThatThrownBy(() -> VectorMinCosineRetrieval.minCosineForSpec("lexical_precision"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> VectorMinCosineRetrieval.minCosineForSpec("0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> VectorMinCosineRetrieval.minCosineForSpec("-0.5"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> VectorMinCosineRetrieval.minCosineForSpec("1.0001"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> VectorMinCosineRetrieval.minCosineForSpec("NaN"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── absent-path identity ─────────────────────────────────────────────

    @Test
    @DisplayName("theta <= 0 returns the DELEGATE ITSELF — never a wrapper (absent-path byte-identity)")
    void absentThetaIsTheBareProvider() {
        RetrievalProvider bare = new ScoredProvider(List.of());
        assertThat(VectorMinCosineRetrieval.maybeWrap(bare, 0)).isSameAs(bare);
        assertThat(VectorMinCosineRetrieval.maybeWrap(bare, -0.5)).isSameAs(bare);
    }

    // ── trim semantics ───────────────────────────────────────────────────

    @Test
    @DisplayName("removes candidates below theta; the list under-fills and NEVER backfills")
    void removesBelowThetaWithoutBackfill() {
        // 5 candidates, scores 0.70 / 0.62 / 0.58 / 0.55 / 0.51 — theta 0.60 removes two
        List<RetrievalCandidate> universe = List.of(
                scored("docA", 0, 0.70), scored("docA", 1, 0.62),
                scored("docA", 2, 0.58), scored("docA", 3, 0.55),
                scored("docA", 4, 0.51));
        RetrievalProvider wrapped = VectorMinCosineRetrieval.maybeWrap(
                new ScoredProvider(universe), 0.60);
        List<RetrievalCandidate> out = wrapped.retrieve(query(5));
        assertThat(out).hasSize(2); // under-fill: 5 asked, 2 survive, no backfill
        assertThat(out.stream().map(c -> c.metadata().get("chunk_index")))
                .containsExactly("0", "1"); // survivors keep their positions
    }

    @Test
    @DisplayName("the boundary value is KEPT (>= theta — the production filter's own comparison)")
    void boundaryValueIsKept() {
        List<RetrievalCandidate> universe = List.of(
                scored("docA", 0, 0.60), scored("docA", 1, 0.5999999));
        RetrievalProvider wrapped = VectorMinCosineRetrieval.maybeWrap(
                new ScoredProvider(universe), 0.60);
        List<RetrievalCandidate> out = wrapped.retrieve(query(2));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).metadata().get("chunk_index")).isEqualTo("0");
    }

    @Test
    @DisplayName("pure-filter property: survivors keep native scores and order (the fused RRF stays byte-identical)")
    void survivorsKeepScoresAndOrder() {
        List<RetrievalCandidate> universe = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            universe.add(scored("docA", i, 0.90 - i * 0.05));
        }
        RetrievalProvider wrapped = VectorMinCosineRetrieval.maybeWrap(
                new ScoredProvider(universe), 0.62);
        List<RetrievalCandidate> out = wrapped.retrieve(query(10));
        // 0.90..0.65 (six scores) survive; 0.60..0.50 trimmed — theta off the
        // exact FP boundary so the comparison is wobble-free
        assertThat(out).hasSize(6);
        for (int i = 0; i < out.size(); i++) {
            assertThat(out.get(i).providerScore()).isEqualTo(0.90 - i * 0.05);
            assertThat(out.get(i).metadata().get("chunk_index")).isEqualTo(String.valueOf(i));
        }
        assertThat(VectorMinCosineRetrieval.maybeWrap(new ScoredProvider(universe), 0)
                .retrieve(query(10))).hasSize(10); // absent theta: nothing trimmed
    }

    @Test
    @DisplayName("the removal ledger is idempotent per stripped query text across repeated retrieval")
    void ledgerIsIdempotent() {
        List<RetrievalCandidate> universe = List.of(
                scored("docA", 0, 0.90), scored("docA", 1, 0.40),
                scored("docA", 2, 0.30));
        VectorMinCosineRetrieval.MinCosineProvider wrapped =
                (VectorMinCosineRetrieval.MinCosineProvider) VectorMinCosineRetrieval.maybeWrap(
                        new ScoredProvider(universe), 0.60);
        wrapped.retrieve(query(3));
        wrapped.retrieve(query(3)); // the determinism double-pass: same query text
        wrapped.retrieve(query(3));
        assertThat(wrapped.affectedQueries()).isEqualTo(1);
        assertThat(wrapped.removedTotal()).isEqualTo(2L); // max per query, not a running sum
        assertThat(wrapped.minCosine()).isEqualTo(0.60);
    }

    @Test
    @DisplayName("provider identity and availability delegate through")
    void identityDelegatesThrough() {
        ScoredProvider bare = new ScoredProvider(List.of(scored("docA", 0, 0.9)));
        RetrievalProvider wrapped = VectorMinCosineRetrieval.maybeWrap(bare, 0.5);
        assertThat(wrapped.id()).isEqualTo(bare.id());
        assertThat(wrapped.available()).isTrue();
    }

    // ── six-way one-lever guard ──────────────────────────────────────────

    @Test
    @DisplayName("one lever at a time: min-cosine is exclusive with all five existing levers")
    void minCosineIsExclusiveWithEveryLever() {
        EvidenceReranker reranker = new LexicalPrecisionReranker();
        Map<EvidenceItem.EvidenceSource, Double> weights = Map.of();
        // min-cosine alone is fine
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 0, 0, 0.60))
                .doesNotThrowAnyException();
        // min-cosine + any other lever is a composition error
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, null, 0, 0, 0, 0.60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BENCH_MIN_COSINE");
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, weights, 0, 0, 0, 0.60))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, null, 5, 0, 0, 0.60))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, null, 0, 15, 0, 0.60))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, null, 0, 15, 20, 0.60))
                .isInstanceOf(IllegalStateException.class);
        // the recorded five-way contracts still hold
        assertThatThrownBy(() -> Run005C.requireSingleLever(reranker, weights, 5))
                .isInstanceOf(IllegalStateException.class);
        // the kind-arm + its bound remain ONE lever (the T-C77 contract)
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 15, 20))
                .doesNotThrowAnyException();
        assertThatCode(() -> Run005C.requireSingleLever(null, null, 0, 15, 0))
                .doesNotThrowAnyException();
        // but the bound composed with the min-cosine trim is two levers
        assertThatThrownBy(() -> Run005C.requireSingleLever(null, null, 0, 15, 20, 0.60))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── fakes ────────────────────────────────────────────────────────────

    /** A deterministic arm whose candidate scores ARE the trim's input. */
    private static final class ScoredProvider implements RetrievalProvider {
        private final List<RetrievalCandidate> universe;

        ScoredProvider(List<RetrievalCandidate> universe) {
            this.universe = List.copyOf(universe);
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
            return universe.subList(0, Math.min(query.limit(), universe.size()));
        }
    }

    private static StructuredRetrievalQuery query(int limit) {
        return StructuredRetrievalQuery.of("learner question", SCOPE, limit);
    }

    private static RetrievalCandidate scored(String docId, int ordinal, double cosine) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("chunk_index", String.valueOf(ordinal));
        return new RetrievalCandidate("fake", null, docId, 1, docId + ":" + ordinal,
                null, null, "content " + ordinal, cosine, null, null, metadata);
    }
}

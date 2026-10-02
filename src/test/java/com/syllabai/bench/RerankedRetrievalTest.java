package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.retrieval.BoundaryPolicy;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalFabric;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import com.syllabai.tutor.ReciprocalRankFusion;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Arm D's composition seam (T-C63): the reranker reorders what fusion produced
 * and NOTHING else — the pre-fusion boundary is structurally unreachable from
 * the rerank step, identity is preserved (permutation, fail-closed on breach),
 * and the NoReranker path is the bare fabric (absent-path identity).
 */
class RerankedRetrievalTest {

    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");
    private static final CurriculumScope SCOPE = new CurriculumScope(CV_ID, "4CH1-IT", Set.of());

    private static RetrievalCandidate chunk(UUID chunkId, String documentId, String content) {
        return new RetrievalCandidate("test-arm", null, documentId, 1,
                chunkId.toString(), null, null, content, 0.0,
                null, null, Map.of());
    }

    /** Scripted provider: returns the queued list once per retrieve call. */
    private static final class ScriptedProvider implements RetrievalProvider {
        private final String id;
        private final List<RetrievalCandidate> script;

        ScriptedProvider(String id, List<RetrievalCandidate> script) {
            this.id = id;
            this.script = script;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
            return script;
        }
    }

    private static StructuredRetrievalQuery query(int limit) {
        return StructuredRetrievalQuery.of("electrolysis releases the metal", SCOPE, limit);
    }

    @Test
    @DisplayName("the reranker lifts the query-matching candidate over agreement-ranked mass")
    void rerankLiftsQueryMatch() {
        // arm-1 ranks a no-match candidate first (agreement mass); the query-
        // matching candidate sits deep. Arm D must lift it.
        RetrievalCandidate mass = chunk(
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                "doc-mass", "unrelated boilerplate entirely");
        RetrievalCandidate gold = chunk(
                UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
                "doc-gold", "electrolysis releases the metal at the cathode");
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(new ScriptedProvider("arm-1", List.of(mass, gold))),
                new ReciprocalRankFusion(60), BoundaryPolicy.allowAll());
        RerankedRetrieval.FusedRetriever armD =
                RerankedRetrieval.maybeWrap(fabric, new LexicalPrecisionReranker());

        List<RetrievalFabric.FusedCandidate> reranked = armD.retrieve(query(10));

        assertThat(reranked.get(0).candidate().evidenceLocator())
                .isEqualTo(gold.evidenceLocator());
        // permutation: same set, same size, both present
        assertThat(reranked).hasSize(2);
    }

    @Test
    @DisplayName("boundary non-circumvention: an excluded candidate can never re-enter via rerank")
    void boundaryStaysClosed() {
        RetrievalCandidate eligible = chunk(
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                "doc-ok", "electrolysis releases the metal");
        RetrievalCandidate excluded = chunk(
                UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
                "doc-blocked", "electrolysis releases the metal on a REJECTED paper");
        // the provider returns both; the boundary policy excludes "doc-blocked"
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(new ScriptedProvider("arm-1", List.of(eligible, excluded))),
                new ReciprocalRankFusion(60),
                candidate -> !"doc-blocked".equals(candidate.documentId()));
        RerankedRetrieval.FusedRetriever armD =
                RerankedRetrieval.maybeWrap(fabric, new LexicalPrecisionReranker());

        List<RetrievalFabric.FusedCandidate> reranked = armD.retrieve(query(10));

        assertThat(reranked).hasSize(1);
        assertThat(reranked.get(0).candidate().documentId()).isEqualTo("doc-ok");
    }

    @Test
    @DisplayName("maybeWrap(null) is the bare fabric: identical order, no wrapping")
    void absentPathIsTheBareFabric() {
        RetrievalCandidate a = chunk(
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                "doc-a", "first");
        RetrievalCandidate b = chunk(
                UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
                "doc-b", "second");
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(new ScriptedProvider("arm-1", List.of(a, b))),
                new ReciprocalRankFusion(60), BoundaryPolicy.allowAll());
        RerankedRetrieval.FusedRetriever bare = RerankedRetrieval.maybeWrap(fabric, null);

        List<RetrievalFabric.FusedCandidate> raw = fabric.retrieve(query(10));
        List<RetrievalFabric.FusedCandidate> wrapped = bare.retrieve(query(10));

        assertThat(wrapped).isEqualTo(raw);
    }

    @Test
    @DisplayName("empty fused output passes through empty")
    void emptyPassThrough() {
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(new ScriptedProvider("arm-1", List.of())),
                new ReciprocalRankFusion(60), BoundaryPolicy.allowAll());
        RerankedRetrieval.FusedRetriever armD =
                RerankedRetrieval.maybeWrap(fabric, new LexicalPrecisionReranker());
        assertThat(armD.retrieve(query(10))).isEmpty();
    }

    @Test
    @DisplayName("unknown reranker spec fails closed; the known spec builds")
    void rerankerRegistryFailClosed() {
        assertThat(RerankedRetrieval.rerankerForSpec(null)).isNull();
        assertThat(RerankedRetrieval.rerankerForSpec("")).isNull();
        assertThat(RerankedRetrieval.rerankerForSpec("lexical_precision"))
                .isInstanceOf(LexicalPrecisionReranker.class);
        assertThatThrownBy(() -> RerankedRetrieval.rerankerForSpec("cross_encoder_v9"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown BENCH_ARM_D_RERANKER spec");
    }
}

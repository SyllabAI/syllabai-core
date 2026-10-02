package com.syllabai.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.retrieval.BoundaryPolicy;
import com.syllabai.retrieval.RetrievalCandidate;
import com.syllabai.retrieval.RetrievalFabric;
import com.syllabai.retrieval.RetrievalProvider;
import com.syllabai.retrieval.StructuredRetrievalQuery;
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.EvidenceReranker;
import com.syllabai.tutor.ReciprocalRankFusion;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-C63 arm D instrument tests. The pins below are HAND-DERIVED from the
 * pre-registered charter formula (never computed by calling the subject with
 * a tuned loop): idf = ln(1 + (N − df + 0.5)/(df + 0.5)); tf-part = tf·(k1+1)/
 * (tf+k1) with k1 = 1.2. For N=3, df=1: idf = ln(1 + 2.5/1.5) = ln(8/3);
 * tf=1 → 1.0; tf=2 → 4.4/3.2 = 1.375. For N=1, df=1: idf = ln(1 + 0.5/1.5)
 * = ln(4/3).
 */
class LexicalPrecisionRerankerTest {

    private static final double IDF_N3_DF1 = Math.log(8.0 / 3.0);
    private static final double IDF_N1_DF1 = Math.log(4.0 / 3.0);

    private static EvidenceItem item(UUID chunkId, String content) {
        return EvidenceItem.fromChunk(null, "doc-1", 1, chunkId, 0, "OTHER",
                content, null, null, List.of(), null, 0.5);
    }

    @Test
    @DisplayName("scores follow the pre-registered formula exactly (hand-derived pins)")
    void scoresFollowPreRegisteredFormula() {
        // query tokens: [electrolysis, ionic] — both df=1 in a pool of N=3
        EvidenceItem a = item(UUID.randomUUID(), "Molten electrolysis electrolysis"); // tf=2
        EvidenceItem b = item(UUID.randomUUID(), "Ionic bonding lattice");            // tf=1
        EvidenceItem c = item(UUID.randomUUID(), "Nothing relevant here at all");     // tf=0
        List<EvidenceItem> out = new LexicalPrecisionReranker().rerank(
                "electrolysis ionic", List.of(a, b, c));

        // ordering: score desc — A (1.375·idf) > B (1.0·idf) > C (0)
        assertThat(out).hasSize(3);
        assertThat(out.get(0).chunkId()).isEqualTo(a.chunkId());
        assertThat(out.get(1).chunkId()).isEqualTo(b.chunkId());
        assertThat(out.get(2).chunkId()).isEqualTo(c.chunkId());
        // exact pins: idf = ln(8/3); tf-part tf=2 → 2·2.2/(2+1.2) = 1.375, tf=1 → 1.0
        assertThat(out.get(0).rerankScore()).isCloseTo(IDF_N3_DF1 * 1.375, within(1e-12));
        assertThat(out.get(1).rerankScore()).isCloseTo(IDF_N3_DF1, within(1e-12));
        assertThat(out.get(2).rerankScore()).isCloseTo(0.0, within(1e-12));
        // rerankScore set on EVERY item (port contract — never null downstream)
        assertThat(out).allSatisfy(i -> assertThat(i.rerankScore()).isNotNull());
    }

    @Test
    @DisplayName("tokenizer splits letter-runs and digit-runs: 4CH1/2 matches query token 2")
    void tokenizationSplitsLetterAndDigitRuns() {
        // N=1 pool, the single query token "2" hits the single candidate →
        // idf = ln(4/3), tf=1 → score = ln(4/3)·1.0 exactly
        EvidenceItem spec = item(UUID.randomUUID(), "4CH1/2");
        List<EvidenceItem> out = new LexicalPrecisionReranker().rerank("2", List.of(spec));
        assertThat(out.get(0).rerankScore()).isCloseTo(IDF_N1_DF1, within(1e-12));

        // spec-code fragments are ordinary tokens: a query of "4ch1 topic 5c"
        // (6 unique tokens) matches a candidate carrying "4CH1/2 topic-5c" on all
        // six letter/digit runs, while a prose-only candidate scores zero.
        // N=2 pool, df=1 per query token → idf = ln(1 + 1.5/1.5) = ln(2)
        EvidenceItem specCode = item(UUID.randomUUID(), "4CH1/2 topic-5c ionic");
        EvidenceItem prose = item(UUID.randomUUID(), "explain something thoroughly");
        List<EvidenceItem> out2 = new LexicalPrecisionReranker().rerank(
                "4CH1 topic 5c", List.of(specCode, prose));
        assertThat(out2.get(0).chunkId()).isEqualTo(specCode.chunkId());
        assertThat(out2.get(0).rerankScore()).isCloseTo(6 * Math.log(2.0), within(1e-12));
        assertThat(out2.get(1).rerankScore()).isCloseTo(0.0, within(1e-12));
    }

    @Test
    @DisplayName("ties break by fused rank ascending (stable over the best-first input)")
    void tiesBreakByFusedRank() {
        EvidenceItem first = item(UUID.randomUUID(), "same text here");
        EvidenceItem second = item(UUID.randomUUID(), "same text here");
        LexicalPrecisionReranker reranker = new LexicalPrecisionReranker();
        assertThat(reranker.rerank("same", List.of(first, second)))
                .extracting(EvidenceItem::chunkId)
                .containsExactly(first.chunkId(), second.chunkId());
        assertThat(reranker.rerank("same", List.of(second, first)))
                .extracting(EvidenceItem::chunkId)
                .containsExactly(second.chunkId(), first.chunkId());
    }

    @Test
    @DisplayName("never invents or drops: the output is a permutation of the input")
    void neverInventsOrDrops() {
        EvidenceItem a = item(UUID.randomUUID(), "alpha");
        EvidenceItem b = item(UUID.randomUUID(), "beta gamma");
        EvidenceItem c = item(UUID.randomUUID(), "alpha beta");
        List<EvidenceItem> out = new LexicalPrecisionReranker().rerank("alpha beta", List.of(a, b, c));
        assertThat(out).hasSameSizeAs(List.of(a, b, c));
        // same provenance set (the reranker returns rescored copies — port
        // contract: rerankScore set on every item — so compare by locator, not
        // by record equality, which would wrongly flag the rescoring itself)
        assertThat(out).extracting(EvidenceItem::chunkId)
                .containsExactlyInAnyOrder(a.chunkId(), b.chunkId(), c.chunkId());
    }

    @Test
    @DisplayName("deterministic: identical inputs produce identical order and scores")
    void deterministic() {
        EvidenceItem a = item(UUID.randomUUID(), "electrolysis of molten compounds");
        EvidenceItem b = item(UUID.randomUUID(), "ionic equations electrolysis");
        LexicalPrecisionReranker reranker = new LexicalPrecisionReranker();
        List<EvidenceItem> first = reranker.rerank("electrolysis ionic", List.of(a, b));
        List<EvidenceItem> second = reranker.rerank("electrolysis ionic", List.of(a, b));
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("blank query: order unchanged, zero score set on every item")
    void blankQuery() {
        EvidenceItem a = item(UUID.randomUUID(), "alpha beta");
        EvidenceItem b = item(UUID.randomUUID(), "gamma");
        List<EvidenceItem> out = new LexicalPrecisionReranker().rerank("", List.of(a, b));
        assertThat(out).extracting(EvidenceItem::chunkId)
                .containsExactly(a.chunkId(), b.chunkId());
        assertThat(out).allSatisfy(i -> assertThat(i.rerankScore()).isCloseTo(0.0, within(1e-12)));
    }

    @Test
    @DisplayName("forName fails closed on anything but the pre-registered name")
    void forNameFailsClosed() {
        assertThat(LexicalPrecisionReranker.forName("lexical_precision"))
                .isInstanceOf(EvidenceReranker.class);
        assertThatThrownBy(() -> LexicalPrecisionReranker.forName("cross_encoder"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");
    }

    // ── RerankedRetrieval (the fabric + reranker wrapper) ─────────────────────

    private static final CurriculumScope SCOPE =
            new CurriculumScope(UUID.fromString("00000000-0000-0000-0000-00000000c001"),
                    "4CH1", Set.of());

    private static RetrievalCandidate chunk(UUID chunkId, String content) {
        return new RetrievalCandidate("pgvector", null, "doc-1", 1, chunkId.toString(),
                null, null, content, 0.5, null, null,
                Map.of("chunk_index", "0", "document_kind", "SYLLABUS"));
    }

    private static RetrievalCandidate kgNode(UUID nodeId, String content) {
        return new RetrievalCandidate("authoritative-kg", null, null, null, nodeId.toString(),
                nodeId, "4CH1-1.26", content, 0.4, null, null,
                Map.of("node_type", "SUBTOPIC", "node_title", "Electrolysis"));
    }

    private static RetrievalProvider oneArm(List<RetrievalCandidate> candidates) {
        return new RetrievalProvider() {
            @Override
            public String id() {
                return "pgvector";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<RetrievalCandidate> retrieve(StructuredRetrievalQuery query) {
                return candidates;
            }
        };
    }

    @Test
    @DisplayName("wrapper reorders the SAME fused candidates downstream of fusion")
    void wrapperReordersTheSameCandidates() {
        UUID chunkA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(oneArm(List.of(
                        chunk(chunkA, "unrelated filler text"),
                        chunk(chunkB, "enthalpy change definition")))),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());

        // fused order (single arm): A rank 1, B rank 2
        List<RetrievalFabric.FusedCandidate> fused = fabric.retrieve(
                StructuredRetrievalQuery.of("enthalpy", SCOPE, 10));
        assertThat(fused).extracting(f -> f.candidate().evidenceLocator())
                .containsExactly(chunkA.toString(), chunkB.toString());

        // reranked: B is the only lexically-matching candidate → flips to first;
        // the SAME FusedCandidate instances come back, none invented or dropped
        List<RetrievalFabric.FusedCandidate> reranked = new RerankedRetrieval(fabric,
                LexicalPrecisionReranker.forName("lexical_precision"))
                .retrieve(StructuredRetrievalQuery.of("enthalpy", SCOPE, 10));
        assertThat(reranked).hasSameSizeAs(fused);
        assertThat(reranked.get(0).candidate()).isSameAs(fused.get(1).candidate());
        assertThat(reranked.get(1).candidate()).isSameAs(fused.get(0).candidate());
        // fused scores untouched — the recorded fusion axis stays the fusion's own
        assertThat(reranked.get(0).fusedScore()).isEqualTo(fused.get(1).fusedScore());
        assertThat(reranked.get(1).fusedScore()).isEqualTo(fused.get(0).fusedScore());
    }

    @Test
    @DisplayName("wrapper bridges KG candidates back by their node locator")
    void wrapperBridgesKgCandidates() {
        UUID chunkA = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(oneArm(List.of(
                        chunk(chunkA, "unrelated filler text"),
                        kgNode(nodeId, "electrolysis of molten compounds")))),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());

        List<RetrievalFabric.FusedCandidate> fused = fabric.retrieve(
                StructuredRetrievalQuery.of("electrolysis", SCOPE, 10));
        List<RetrievalFabric.FusedCandidate> reranked = new RerankedRetrieval(fabric,
                LexicalPrecisionReranker.forName("lexical_precision"))
                .retrieve(StructuredRetrievalQuery.of("electrolysis", SCOPE, 10));

        assertThat(reranked).hasSameSizeAs(fused);
        assertThat(reranked.get(0).candidate()).isSameAs(fused.get(1).candidate());
        assertThat(reranked.get(0).candidate().knowledgeNodeId()).isEqualTo(nodeId);
    }

    @Test
    @DisplayName("wrapper passes an empty fused pool through untouched")
    void wrapperPassesEmptyPoolThrough() {
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(oneArm(List.of())),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());
        List<RetrievalFabric.FusedCandidate> reranked = new RerankedRetrieval(fabric,
                LexicalPrecisionReranker.forName("lexical_precision"))
                .retrieve(StructuredRetrievalQuery.of("anything", SCOPE, 10));
        assertThat(reranked).isEmpty();
    }

    @Test
    @DisplayName("wrapper is a pure function: double pass byte-identical (determinism contract)")
    void wrapperDoublePassIdentical() {
        UUID chunkA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();
        RetrievalFabric fabric = new RetrievalFabric(
                List.of(oneArm(List.of(
                        chunk(chunkA, "unrelated filler text"),
                        chunk(chunkB, "enthalpy change definition")))),
                new ReciprocalRankFusion(60),
                BoundaryPolicy.allowAll());
        RerankedRetrieval wrapped = new RerankedRetrieval(fabric,
                LexicalPrecisionReranker.forName("lexical_precision"));
        assertThat(wrapped.retrieve(StructuredRetrievalQuery.of("enthalpy", SCOPE, 10)))
                .isEqualTo(wrapped.retrieve(StructuredRetrievalQuery.of("enthalpy", SCOPE, 10)));
    }
}

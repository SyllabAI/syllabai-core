package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.syllabai.tutor.EvidenceItem.EvidenceSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Plan §7 per-kind RRF weights on the SERVING fusion posture
 * ({@code fuseWithPlanWeights}, consumed by KaRagService + ClaService).
 * Pins: the canonical weight table, the induced ordering at equal rank, the
 * 1.0 default for unmapped sources (KG anchors keep full influence), agreement
 * accumulation, and the bit-identity of the unweighted overload that bench
 * replays and offline evals depend on.
 */
class ServingPlanWeightsTest {

    private static final double K61 = 1.0 / 61; // rank-0 contribution at k=60

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion(60);

    private static EvidenceItem chunk(EvidenceSource source) {
        String kind = switch (source) {
            case NOTE -> "EXTERNAL_NOTES";
            case TEXTBOOK -> "TEXTBOOK";
            case CARD -> "EXTERNAL_QUESTIONS";
            case SYLLABUS -> "SYLLABUS";
            case MARK_SCHEME -> "MARK_SCHEME";
            case QUESTION_PAPER -> "QUESTION_PAPER";
            default -> "OTHER";
        };
        return EvidenceItem.fromChunk(UUID.randomUUID(), "doc-1", 1, UUID.randomUUID(), 0,
                kind, "content " + source, 1, 2, List.of(), "gemini-embedding-001", 0.9);
    }

    private static EvidenceItem kgNode() {
        return EvidenceItem.fromNode(UUID.randomUUID(), "4CH1.1.22", "TOPIC",
                "Electrolysis", null, 0.9);
    }

    @Test
    @DisplayName("plan §7 weight table is the canonical six-entry map")
    void weightTableIsCanonical() {
        assertThat(ReciprocalRankFusion.PLAN_V2_WEIGHTS).isEqualTo(Map.of(
                EvidenceSource.NOTE, 1.0,
                EvidenceSource.SYLLABUS, 0.9,
                EvidenceSource.QUESTION_PAPER, 0.8,
                EvidenceSource.TEXTBOOK, 0.7,
                EvidenceSource.MARK_SCHEME, 0.6,
                EvidenceSource.CARD, 0.3));
        // fabric aliases the same immutable instance — one source of truth
        assertThat(com.syllabai.retrieval.RetrievalFabric.PLAN_V2_WEIGHTS)
                .isSameAs(ReciprocalRankFusion.PLAN_V2_WEIGHTS);
    }

    @Test
    @DisplayName("equal rank: KG anchor outranks QUESTION_PAPER; QUESTION_PAPER outranks MARK_SCHEME")
    void kgOutranksQuestionPaperOutranksMarkScheme() {
        List<EvidenceItem> fused = fusion.fuseWithPlanWeights(
                List.of(List.of(kgNode()), List.of(chunk(EvidenceSource.QUESTION_PAPER))));
        assertThat(fused.get(0).source()).isEqualTo(EvidenceSource.KNOWLEDGE_NODE);
        assertThat(fused.get(0).fusedScore()).isCloseTo(K61, within(1e-12));
        assertThat(fused.get(1).fusedScore()).isCloseTo(0.8 * K61, within(1e-12));

        List<EvidenceItem> qpVsMs = fusion.fuseWithPlanWeights(
                List.of(List.of(chunk(EvidenceSource.QUESTION_PAPER)),
                        List.of(chunk(EvidenceSource.MARK_SCHEME))));
        assertThat(qpVsMs.get(0).source()).isEqualTo(EvidenceSource.QUESTION_PAPER);
        assertThat(qpVsMs.get(1).source()).isEqualTo(EvidenceSource.MARK_SCHEME);
    }

    @Test
    @DisplayName("equal rank: NOTE 1.0 > SYLLABUS 0.9 > TEXTBOOK 0.7 > CARD 0.3 ordering")
    void knowledgeLayerLeadsCardsTrail() {
        List<EvidenceItem> fused = fusion.fuseWithPlanWeights(List.of(
                List.of(chunk(EvidenceSource.NOTE)),
                List.of(chunk(EvidenceSource.SYLLABUS)),
                List.of(chunk(EvidenceSource.TEXTBOOK)),
                List.of(chunk(EvidenceSource.CARD))));
        assertThat(fused).extracting(EvidenceItem::source)
                .containsExactly(EvidenceSource.NOTE, EvidenceSource.SYLLABUS,
                        EvidenceSource.TEXTBOOK, EvidenceSource.CARD);
    }

    @Test
    @DisplayName("sources absent from the map (KG node, OTHER) weigh 1.0")
    void unmappedSourcesWeighOne() {
        List<EvidenceItem> fused = fusion.fuseWithPlanWeights(
                List.of(List.of(chunk(EvidenceSource.OTHER))));
        assertThat(fused.get(0).fusedScore()).isCloseTo(K61, within(1e-12));

        List<EvidenceItem> kg = fusion.fuseWithPlanWeights(List.of(List.of(kgNode())));
        assertThat(kg.get(0).fusedScore()).isCloseTo(K61, within(1e-12));
    }

    @Test
    @DisplayName("agreement accumulates scaled contributions")
    void agreementAccumulatesScaled() {
        EvidenceItem note = chunk(EvidenceSource.NOTE);
        List<EvidenceItem> fused = fusion.fuseWithPlanWeights(List.of(List.of(note), List.of(note)));
        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).fusedScore()).isCloseTo(2 * K61, within(1e-12));
    }

    @Test
    @DisplayName("unweighted overload is bit-identical to weight 1.0 everywhere (bench-replay posture)")
    void unweightedOverloadBitIdentical() {
        List<EvidenceItem> mixed = List.of(kgNode(), chunk(EvidenceSource.MARK_SCHEME),
                chunk(EvidenceSource.QUESTION_PAPER), chunk(EvidenceSource.NOTE));
        List<List<EvidenceItem>> lists = List.of(mixed, List.of(chunk(EvidenceSource.CARD)));

        List<EvidenceItem> unweighted = fusion.fuse(lists);
        List<EvidenceItem> explicitOnes = fusion.fuse(lists, item -> 1.0);
        assertThat(unweighted).hasSameSizeAs(explicitOnes);
        for (int i = 0; i < unweighted.size(); i++) {
            assertThat(unweighted.get(i).fusedScore())
                    .isEqualTo(explicitOnes.get(i).fusedScore());
        }
    }

    @Test
    @DisplayName("rank order inside a list is untouched — weights only rescale list influence")
    void rankOrderInsideListUntouched() {
        EvidenceItem msRank0 = chunk(EvidenceSource.MARK_SCHEME);
        EvidenceItem msRank1 = chunk(EvidenceSource.MARK_SCHEME);
        List<EvidenceItem> fused = fusion.fuseWithPlanWeights(
                List.of(List.of(msRank0, msRank1)));
        assertThat(fused.get(0).fusedScore()).isCloseTo(0.6 * K61, within(1e-12));
        assertThat(fused.get(1).fusedScore()).isCloseTo(0.6 / 62, within(1e-12));
        assertThat(fused.get(0).fusedScore()).isGreaterThan(fused.get(1).fusedScore());
    }
}

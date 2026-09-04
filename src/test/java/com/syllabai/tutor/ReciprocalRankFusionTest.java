package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RRF fusion (T-024): rank-only math, agreement across sources is rewarded,
 * ties break deterministically, ungrounded candidates are rejected.
 */
class ReciprocalRankFusionTest {

    private final ReciprocalRankFusion fusion = new ReciprocalRankFusion(60);

    @Test
    @DisplayName("candidates in both rankings out-rank candidates in one")
    void agreementWins() {
        EvidenceItem kgOnly = node("A", 0.9);
        EvidenceItem both = node("B", 0.5);
        EvidenceItem vectorOnly = chunk("doc-1", 7, 0.42);

        List<EvidenceItem> fused = fusion.fuse(List.of(
                List.of(kgOnly, both),                       // KG ranking
                List.of(vectorOnly, both)));                  // vector ranking

        assertThat(fused).hasSize(3);
        // both = 1/61 + 1/62 > any single contribution
        assertThat(fused.get(0).nodeCode()).isEqualTo("B");
        assertThat(fused.get(0).fusedScore()).isGreaterThan(fused.get(1).fusedScore());
        // every survivor carries a fused score; retrieval scores are untouched
        assertThat(fused).allSatisfy(e -> {
            assertThat(e.fusedScore()).isPositive();
            assertThat(e.rerankScore()).isNull();
        });
        assertThat(fused.get(0).retrievalScore()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("identical inputs fuse to identical order (determinism, §19)")
    void deterministicOrdering() {
        EvidenceItem a = node("A", 0.3);
        EvidenceItem b = node("B", 0.9);
        List<List<EvidenceItem>> input = List.of(List.of(a, b), List.of(b, a));

        List<EvidenceItem> first = fusion.fuse(input);
        List<EvidenceItem> second = fusion.fuse(input);

        assertThat(first).extracting(EvidenceItem::nodeCode)
                .containsExactlyElementsOf(second.stream().map(EvidenceItem::nodeCode).toList());
        assertThat(first).hasSize(2);
    }

    @Test
    @DisplayName("empty inputs fuse to empty; ungrounded items never fuse")
    void emptyAndUngrounded() {
        assertThat(fusion.fuse(List.of())).isEmpty();
        assertThat(fusion.fuse(List.of(List.of()))).isEmpty();

        // a chunk with no chunkId and no nodeId has no fusion key
        EvidenceItem ungrounded = EvidenceItem.fromChunk(null, "doc-1", 1, null, 0,
                "MARK_SCHEME", "content", 1, 1, List.of(), "gemini", 0.9);
        assertThat(fusion.fuse(List.of(List.of(ungrounded)))).isEmpty();
    }

    @Test
    @DisplayName("non-positive k is rejected at construction")
    void rejectsBadK() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ReciprocalRankFusion(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EvidenceItem node(String code, double score) {
        return EvidenceItem.fromNode(UUID.randomUUID(), code, "TOPIC", "title " + code, null, score);
    }

    private EvidenceItem chunk(String documentId, int chunkIndex, double score) {
        return EvidenceItem.fromChunk(UUID.randomUUID(), documentId, 1, UUID.randomUUID(),
                chunkIndex, "MARK_SCHEME", "content " + documentId, 6, 6, List.of(), "gemini", score);
    }
}

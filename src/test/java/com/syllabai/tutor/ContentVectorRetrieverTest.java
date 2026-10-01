package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.content.ChunkHit;
import com.syllabai.content.ContentRetrievalService;
import com.syllabai.curriculum.CurriculumScope;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Vector adapter (T-024): chunk hits lift into evidence with provenance;
 * sub-threshold similarity is NOT evidence (pgvector ranks return zero-relevance
 * chunks too); a missing embedding provider degrades to empty, not failure.
 * Document version arrives carried by the search SQL (M2/T-C32-class N+1 kill:
 * the adapter has no DocumentRepository and never re-reads a document row).
 */
class ContentVectorRetrieverTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.fromString("00000000-0000-0000-0000-0000000004c1"), "4CH1-2017",
            Set.of(UUID.randomUUID()));

    private final ContentRetrievalService retrieval = mock(ContentRetrievalService.class);
    private final ContentVectorRetriever adapter = new ContentVectorRetriever(retrieval);

    @Test
    @DisplayName("chunks lift into evidence with document provenance intact — version carried by the hit, no per-hit re-read")
    void liftsChunks() {
        UUID docRow = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(retrieval.search(any(), isNull(), org.mockito.ArgumentMatchers.eq(SCOPE), anyInt())).thenReturn(List.of(
                new ChunkHit(chunkId, docRow, 3, "ms-1", "MARK_SCHEME", 4,
                        "accept: chlorine is oxidised", 16, 16, List.of("e26"),
                        "gemini", 0.81)));

        List<EvidenceItem> evidence = adapter.retrieve("chlorine oxidation", 10, SCOPE);

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).source()).isEqualTo(EvidenceItem.EvidenceSource.MARK_SCHEME);
        assertThat(evidence.get(0).documentId()).isEqualTo("ms-1");
        assertThat(evidence.get(0).documentVersion()).isEqualTo(3);
        assertThat(evidence.get(0).chunkId()).isEqualTo(chunkId);
        assertThat(evidence.get(0).pageStart()).isEqualTo(16);
        assertThat(evidence.get(0).elementIds()).containsExactly("e26");
        assertThat(evidence.get(0).retrievalScore()).isEqualTo(0.81);
        assertThat(evidence.get(0).topicIds()).isEmpty();
    }

    @Test
    @DisplayName("sub-threshold similarity is dropped — zero-relevance chunks are not evidence")
    void dropsSubThreshold() {
        when(retrieval.search(any(), isNull(), org.mockito.ArgumentMatchers.eq(SCOPE), anyInt())).thenReturn(List.of(
                hit(0.81), hit(0.15), hit(0.14), hit(0.0), hit(-0.2)));

        List<EvidenceItem> evidence = adapter.retrieve("query", 10, SCOPE);

        assertThat(evidence).hasSize(2);   // 0.81 and the boundary 0.15 survive
    }

    @Test
    @DisplayName("no embedding provider configured → empty candidates, not an exception")
    void degradesWithoutProvider() {
        when(retrieval.search(any(), isNull(), org.mockito.ArgumentMatchers.eq(SCOPE), anyInt()))
                .thenThrow(new IllegalStateException("no embedding provider configured"));

        assertThat(adapter.retrieve("query", 10, SCOPE)).isEmpty();
    }

    @Test
    @DisplayName("null curriculum scope is rejected — retrieval never runs unscoped (T-C07)")
    void rejectsNullScope() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> adapter.retrieve("query", 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never runs unscoped");
    }

    private ChunkHit hit(double score) {
        return new ChunkHit(UUID.randomUUID(), UUID.randomUUID(), 1, "d", "MARK_SCHEME", 0,
                "content", 1, 1, List.of(), "m", score);
    }
}

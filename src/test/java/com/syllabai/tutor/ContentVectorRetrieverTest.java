package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.content.ChunkHit;
import com.syllabai.content.ContentRetrievalService;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Vector adapter (T-024): chunk hits lift into evidence with provenance;
 * sub-threshold similarity is NOT evidence (pgvector ranks return zero-relevance
 * chunks too); a missing embedding provider degrades to empty, not failure.
 */
class ContentVectorRetrieverTest {

    private final ContentRetrievalService retrieval = mock(ContentRetrievalService.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final ContentVectorRetriever adapter =
            new ContentVectorRetriever(retrieval, documents);

    @Test
    @DisplayName("chunks lift into evidence with document provenance intact")
    void liftsChunks() {
        UUID docRow = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        Document doc = new Document("ms-1", "1.0", 3, Document.Kind.MARK_SCHEME,
                "test://ms.pdf", "ms.pdf", "application/pdf", "c".repeat(64), "SHA-256",
                20, 25, 18, 3, "opendataloader-pdf", "2.5.7", null, "{}", null);
        when(documents.findById(docRow)).thenReturn(Optional.of(doc));
        when(retrieval.search(any(), isNull(), anyInt())).thenReturn(List.of(
                new ChunkHit(chunkId, docRow, "ms-1", "MARK_SCHEME", 4,
                        "accept: chlorine is oxidised", 16, 16, List.of("e26"),
                        "gemini", 0.81)));

        List<EvidenceItem> evidence = adapter.retrieve("chlorine oxidation", 10);

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
        when(retrieval.search(any(), isNull(), anyInt())).thenReturn(List.of(
                hit(0.81), hit(0.15), hit(0.14), hit(0.0), hit(-0.2)));

        List<EvidenceItem> evidence = adapter.retrieve("query", 10);

        assertThat(evidence).hasSize(2);   // 0.81 and the boundary 0.15 survive
    }

    @Test
    @DisplayName("no embedding provider configured → empty candidates, not an exception")
    void degradesWithoutProvider() {
        when(retrieval.search(any(), isNull(), anyInt()))
                .thenThrow(new IllegalStateException("no embedding provider configured"));

        assertThat(adapter.retrieve("query", 10)).isEmpty();
    }

    private ChunkHit hit(double score) {
        return new ChunkHit(UUID.randomUUID(), UUID.randomUUID(), "d", "MARK_SCHEME", 0,
                "content", 1, 1, List.of(), "m", score);
    }
}

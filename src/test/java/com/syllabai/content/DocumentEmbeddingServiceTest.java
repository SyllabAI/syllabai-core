package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.shared.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T-013: embedding is idempotent (pending chunks only), dimension mismatches fail
 * loudly instead of poisoning the index, and a missing provider reports exactly
 * what to configure — never a silent skip.
 */
class DocumentEmbeddingServiceTest {

    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final ChunkVectorRepository vectors = mock(ChunkVectorRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmbeddingProvider> provider = mock(ObjectProvider.class);

    private final DocumentEmbeddingService service =
            new DocumentEmbeddingService(provider, chunks, vectors, documents,
                    // mock PTM: getTransaction/commit are no-ops, the store callback still runs
                    new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private final UUID docId = UUID.randomUUID();
    private final Document doc = new Document("canonical-1", "1.0", 1,
            Document.Kind.MARK_SCHEME, "ms.pdf", "ms.pdf", "application/pdf", "abc", "SHA-256",
            4, 10, 10, 2, "opendataloader-pdf", "2.5.7", null, "{}", null);

    private static DocumentChunk pending(int index) {
        return new DocumentChunk(null, index, "content " + index, 1, 1,
                List.of("e" + index), 5);
    }

    @Test
    @DisplayName("all pending chunks embed with the provider's model stamp")
    void embedsPending() {
        when(documents.findById(docId)).thenReturn(Optional.of(doc));
        List<DocumentChunk> pending = List.of(pending(0), pending(1));
        when(chunks.findPendingByDocumentRowId(docId)).thenReturn(pending);
        when(chunks.countByDocumentRowId(docId)).thenReturn(2L);
        when(provider.getIfAvailable()).thenReturn(new StubProvider(768, 768));

        DocumentEmbeddingService.EmbeddingResult result = service.embedDocument(docId);

        assertThat(result.model()).isEqualTo("stub");
        assertThat(result.embedded()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        verify(vectors, times(2)).storeEmbedding(any(), any(), any());
        assertThat(pending.get(0).embeddedAt()).isNotNull();
        assertThat(pending.get(0).embeddingModel()).isEqualTo("stub");
    }

    @Test
    @DisplayName("already-embedded chunks are skipped — re-running is a no-op")
    void idempotent() {
        when(documents.findById(docId)).thenReturn(Optional.of(doc));
        when(chunks.findPendingByDocumentRowId(docId)).thenReturn(List.of());
        when(chunks.countByDocumentRowId(docId)).thenReturn(2L);
        when(provider.getIfAvailable()).thenReturn(new StubProvider(768, 768));

        DocumentEmbeddingService.EmbeddingResult result = service.embedDocument(docId);

        assertThat(result.embedded()).isZero();
        assertThat(result.skipped()).isEqualTo(2);
        verify(vectors, org.mockito.Mockito.never()).storeEmbedding(any(), any(), any());
    }

    @Test
    @DisplayName("a provider whose vectors contradict its declared dimension fails loudly")
    void dimensionMismatchFails() {
        // claims 768 (matches the column) but emits 128-wide vectors
        when(documents.findById(docId)).thenReturn(Optional.of(doc));
        when(chunks.findPendingByDocumentRowId(docId)).thenReturn(List.of(pending(0)));
        when(provider.getIfAvailable()).thenReturn(new StubProvider(768, 128));

        assertThatThrownBy(() -> service.embedDocument(docId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("128")
                .hasMessageContaining("expected 768");
    }

    @Test
    @DisplayName("no provider configured says exactly what to set")
    void noProviderFailsLoudly() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.embedDocument(docId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SYLLABAI_EMBEDDING_GEMINI_API_KEY");
    }

    @Test
    @DisplayName("unknown document is a 404")
    void unknownDocument() {
        when(provider.getIfAvailable()).thenReturn(new StubProvider(768, 768));
        when(documents.findById(docId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.embedDocument(docId))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Deterministic test double. {@code declaredDimension} is what the provider claims
     * (and the index column width); {@code actualDimension} is what its vectors really
     * carry — unequal values model a misbehaving provider.
     */
    static final class StubProvider implements EmbeddingProvider {

        private final int declaredDimension;
        private final int actualDimension;

        StubProvider(int declaredDimension, int actualDimension) {
            this.declaredDimension = declaredDimension;
            this.actualDimension = actualDimension;
        }

        @Override
        public String model() {
            return "stub";
        }

        @Override
        public int dimension() {
            return declaredDimension;
        }

        @Override
        public float[] embedDocument(String text) {
            return new float[actualDimension];
        }

        @Override
        public float[] embedQuery(String text) {
            return new float[actualDimension];
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(t -> new float[actualDimension]).toList();
        }
    }
}

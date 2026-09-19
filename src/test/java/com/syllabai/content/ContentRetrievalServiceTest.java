package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.curriculum.CurriculumScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * T-013: retrieval fails loudly without a provider or with a blank query; limits
 * are clamped server-side; the kind filter passes through to the vector store.
 * T-C07: the curriculum scope is a mandatory argument — a null scope is rejected
 * before anything runs (retrieval never serves unscoped), and the scope's
 * curriculum version id is what reaches the vector store predicate.
 */
class ContentRetrievalServiceTest {

    private static final UUID CV_ID = UUID.fromString("00000000-0000-0000-0000-0000000004c1");

    private static final CurriculumScope SCOPE =
            new CurriculumScope(CV_ID, "4CH1-2017", Set.of(UUID.randomUUID()));

    private final ChunkVectorRepository vectors = mock(ChunkVectorRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<EmbeddingProvider> provider = mock(ObjectProvider.class);

    private final ContentRetrievalService service =
            new ContentRetrievalService(provider, vectors);

    private final RecordingProvider recording = new RecordingProvider(768, 768);

    @Test
    @DisplayName("blank queries are rejected")
    void blankQuery() {
        when(provider.getIfAvailable()).thenReturn(recording);
        assertThatThrownBy(() -> service.search("   ", null, SCOPE, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null curriculum scope is rejected before anything runs (T-C07)")
    void nullScope() {
        when(provider.getIfAvailable()).thenReturn(recording);
        assertThatThrownBy(() -> service.search("rate of reaction", null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("never runs unscoped");
        assertThat(recording.queries).isEmpty(); // nothing was embedded
    }

    @Test
    @DisplayName("no provider configured says exactly what to set")
    void noProvider() {
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.search("rate of reaction", null, SCOPE, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SYLLABAI_EMBEDDING_GEMINI_API_KEY");
    }

    @Test
    @DisplayName("search embeds the query and delegates with a clamped limit and the scope's cv id")
    void happyPath() {
        when(provider.getIfAvailable()).thenReturn(recording);
        List<ChunkHit> expected = List.of(new ChunkHit(UUID.randomUUID(), UUID.randomUUID(),
                "doc-1", "MARK_SCHEME", 0, "content", 1, 1, List.of("e0"),
                "gemini-embedding-001", 0.98));
        when(vectors.search(any(), eq(Document.Kind.MARK_SCHEME), eq(CV_ID), eq(10)))
                .thenReturn(expected);

        List<ChunkHit> hits = service.search("rate of reaction", Document.Kind.MARK_SCHEME, SCOPE, 10);

        assertThat(hits).isSameAs(expected);
        assertThat(recording.queries).containsExactly("rate of reaction"); // stripped
    }

    @Test
    @DisplayName("limits are clamped into 1..50 no matter what the caller sends")
    void limitClamped() {
        when(provider.getIfAvailable()).thenReturn(recording);
        when(vectors.search(any(), any(), any(), eq(1))).thenReturn(List.of());
        when(vectors.search(any(), any(), any(), eq(50))).thenReturn(List.of());

        service.search("q", null, SCOPE, -5);
        service.search("q", null, SCOPE, 5000);

        verify(vectors).search(any(), any(), eq(CV_ID), eq(1));
        verify(vectors).search(any(), any(), eq(CV_ID), eq(50));
        assertThat(recording.queries).hasSize(2);
    }

    @Test
    @DisplayName("a provider contradicting its declared dimension fails loudly")
    void inconsistentVector() {
        when(provider.getIfAvailable()).thenReturn(new RecordingProvider(768, 128));
        assertThatThrownBy(() -> service.search("rate", null, SCOPE, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent query vector");
    }

    /** Records every query text; vectors may contradict the declared dimension on purpose. */
    static final class RecordingProvider implements EmbeddingProvider {

        private final int declaredDimension;
        private final int actualDimension;
        final List<String> queries = new ArrayList<>();

        RecordingProvider(int declaredDimension, int actualDimension) {
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
            queries.add(text);
            return new float[actualDimension];
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            return texts.stream().map(t -> new float[actualDimension]).toList();
        }
    }
}

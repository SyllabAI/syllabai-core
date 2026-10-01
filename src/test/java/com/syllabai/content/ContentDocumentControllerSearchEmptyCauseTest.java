package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.curriculum.CurriculumScopeResolver;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

/**
 * T-C31: the empty-cause header is additive and empty-path-only. The JSON body
 * stays a bare array on every path (wire-compatible); the happy path never
 * runs a diagnostic query; an unresolved scope is labelled without touching
 * any repository (the T-C07 refusal needs no SQL to be explained).
 */
class ContentDocumentControllerSearchEmptyCauseTest {

    private static final UUID REQUESTER =
            UUID.fromString("00000000-0000-0000-0000-00000000e0c1");
    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");
    private static final CurriculumScope SCOPE =
            new CurriculumScope(CV_ID, "IAL-CHEM-2017", Set.of());

    private final CurriculumScopeResolver scopes = mock(CurriculumScopeResolver.class);
    private final ChunkVectorRepository vectors = mock(ChunkVectorRepository.class);
    private final ContentRetrievalService retrieval =
            new ContentRetrievalService(provider(), vectors);
    private final ContentDocumentController controller = new ContentDocumentController(
            mock(ContentIngestionService.class), mock(DocumentEmbeddingService.class),
            retrieval, mock(DocumentRepository.class), scopes);

    private static ObjectProvider<EmbeddingProvider> provider() {
        EmbeddingProvider embedding = mock(EmbeddingProvider.class);
        when(embedding.model()).thenReturn("test-embed");
        when(embedding.dimension()).thenReturn(768);
        when(embedding.embedQuery(anyString())).thenReturn(new float[768]);
        ObjectProvider<EmbeddingProvider> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(embedding);
        return provider;
    }

    @BeforeEach
    void scopeResolves() {
        when(scopes.resolveActive(REQUESTER)).thenReturn(Optional.of(SCOPE));
    }

    @Test
    @DisplayName("non-empty result: no empty-cause header, body unchanged, ZERO diagnostic queries")
    void happyPathRunsNoDiagnostics() {
        when(vectors.searchServingEligible(any(float[].class), any(), eq(CV_ID), anyInt()))
                .thenReturn(List.of(new ChunkHit(UUID.randomUUID(), UUID.randomUUID(),
                        "doc-1", "MARK_SCHEME", 0, "content", 1, 2, List.of(),
                        "test-embed", 0.9)));

        ResponseEntity<List<ContentDocumentController.ChunkHitView>> response =
                controller.search(REQUESTER, "electrolysis", Document.Kind.MARK_SCHEME, 10, null);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getHeaders().getFirst(
                ContentDocumentController.EMPTY_CAUSE_HEADER)).isNull();
        verify(vectors, never()).diagnoseEmpty(any(), any());
    }

    @Test
    @DisplayName("empty result with resolved scope: header names the cause, body stays [], funnel computed once")
    void emptyResultCarriesCauseHeader() {
        when(vectors.searchServingEligible(any(float[].class), any(), eq(CV_ID), anyInt()))
                .thenReturn(List.of());
        when(vectors.diagnoseEmpty(Document.Kind.MARK_SCHEME, CV_ID))
                .thenReturn(new SearchEmptyDiagnostics(30, 30, 0, 0));

        ResponseEntity<List<ContentDocumentController.ChunkHitView>> response =
                controller.search(REQUESTER, "electrolysis", Document.Kind.MARK_SCHEME, 10, null);

        assertThat(response.getBody()).isEmpty();
        assertThat(response.getHeaders().getFirst(
                ContentDocumentController.EMPTY_CAUSE_HEADER))
                .isEqualTo(SearchEmptyCause.EMBED_REV_EMPTY.name());
        verify(vectors).diagnoseEmpty(Document.Kind.MARK_SCHEME, CV_ID);
    }

    @Test
    @DisplayName("unresolved scope: SCOPE_UNRESOLVED header without any retrieval or repository call")
    void unresolvedScopeNeedsNoSqlToExplain() {
        when(scopes.resolveActive(REQUESTER)).thenReturn(Optional.empty());

        ResponseEntity<List<ContentDocumentController.ChunkHitView>> response =
                controller.search(REQUESTER, "electrolysis", null, 10, null);

        assertThat(response.getBody()).isEmpty();
        assertThat(response.getHeaders().getFirst(
                ContentDocumentController.EMPTY_CAUSE_HEADER))
                .isEqualTo(SearchEmptyCause.SCOPE_UNRESOLVED.name());
        verify(vectors, never()).searchServingEligible(any(), any(), any(), anyInt());
        verify(vectors, never()).diagnoseEmpty(any(), any());
    }
}

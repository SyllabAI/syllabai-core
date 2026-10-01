package com.syllabai.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

/**
 * ADR-030 follow-through: the teacher content search accepts an optional
 * courseRef and resolves it through the V53 fail-closed per-course path.
 * The pins: a present ref NEVER touches the global resolver (no fallback —
 * a wrong-corpus answer is worse than an empty one); an unresolved ref is
 * an empty result labelled {@code COURSE_REF_UNRESOLVED} with zero retrieval
 * and zero diagnostic queries; blank/absent keeps the legacy
 * {@code resolveActive} path exactly as T-C31 pinned it.
 */
class ContentDocumentControllerCourseSearchTest {

    private static final UUID REQUESTER =
            UUID.fromString("00000000-0000-0000-0000-00000000e0c2");
    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c2");
    private static final String REF = "4CH1-2017";
    private static final CurriculumScope SCOPE =
            new CurriculumScope(CV_ID, REF, Set.of());

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

    @Test
    @DisplayName("courseRef present: per-course scope reaches retrieval, the global resolver is never touched")
    void courseTaggedSearchResolvesPerCourse() {
        when(scopes.resolveForCourse(REF)).thenReturn(Optional.of(SCOPE));
        when(vectors.searchServingEligible(any(float[].class), any(), eq(CV_ID), anyInt()))
                .thenReturn(List.of(new ChunkHit(UUID.randomUUID(), UUID.randomUUID(),
                        "doc-1", "MARK_SCHEME", 0, "content", 1, 2, List.of(),
                        "test-embed", 0.9)));

        ResponseEntity<List<ContentDocumentController.ChunkHitView>> response =
                controller.search(REQUESTER, "electrolysis", Document.Kind.MARK_SCHEME, 10, REF);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getHeaders().getFirst(
                ContentDocumentController.EMPTY_CAUSE_HEADER)).isNull();
        verify(scopes, never()).resolveActive(any());
    }

    @Test
    @DisplayName("unresolved courseRef: COURSE_REF_UNRESOLVED, zero retrieval, zero diagnostics, NO global fallback")
    void unresolvedRefRefusesFailClosed() {
        when(scopes.resolveForCourse(REF)).thenReturn(Optional.empty());

        ResponseEntity<List<ContentDocumentController.ChunkHitView>> response =
                controller.search(REQUESTER, "electrolysis", null, 10, REF);

        assertThat(response.getBody()).isEmpty();
        assertThat(response.getHeaders().getFirst(
                ContentDocumentController.EMPTY_CAUSE_HEADER))
                .isEqualTo(SearchEmptyCause.COURSE_REF_UNRESOLVED.name());
        verify(scopes, never()).resolveActive(any());
        verify(vectors, never()).searchServingEligible(any(), any(), any(), anyInt());
        verify(vectors, never()).diagnoseEmpty(any(), any());
    }

    @Test
    @DisplayName("blank courseRef is absent: legacy resolveActive path, resolveForCourse never called")
    void blankRefKeepsLegacyPath() {
        when(scopes.resolveActive(REQUESTER)).thenReturn(Optional.of(SCOPE));
        when(vectors.searchServingEligible(any(float[].class), any(), eq(CV_ID), anyInt()))
                .thenReturn(List.of(new ChunkHit(UUID.randomUUID(), UUID.randomUUID(),
                        "doc-2", "NOTES", 0, "content", 1, 2, List.of(),
                        "test-embed", 0.8)));

        ResponseEntity<List<ContentDocumentController.ChunkHitView>> response =
                controller.search(REQUESTER, "electrolysis", Document.Kind.NOTES, 10, "   ");

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getHeaders().getFirst(
                ContentDocumentController.EMPTY_CAUSE_HEADER)).isNull();
        verify(scopes).resolveActive(REQUESTER);
        verify(scopes, never()).resolveForCourse(anyString());
    }

    @Test
    @DisplayName("absent courseRef: legacy resolveActive path byte-identical (pilot parity pin)")
    void absentRefKeepsLegacyPath() {
        when(scopes.resolveActive(REQUESTER)).thenReturn(Optional.of(SCOPE));
        when(vectors.searchServingEligible(any(float[].class), any(), eq(CV_ID), anyInt()))
                .thenReturn(List.of(new ChunkHit(UUID.randomUUID(), UUID.randomUUID(),
                        "doc-3", "NOTES", 0, "content", 1, 2, List.of(),
                        "test-embed", 0.7)));

        ResponseEntity<List<ContentDocumentController.ChunkHitView>> response =
                controller.search(REQUESTER, "electrolysis", Document.Kind.NOTES, 10, null);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getHeaders().getFirst(
                ContentDocumentController.EMPTY_CAUSE_HEADER)).isNull();
        verify(scopes).resolveActive(REQUESTER);
        verify(scopes, never()).resolveForCourse(anyString());
    }

    @Test
    @DisplayName("over-long courseRef fails closed as a plain unresolved ref — no error, no 4xx, no retrieval")
    void overlongRefFailsClosed() {
        String longRef = "X".repeat(129);
        when(scopes.resolveForCourse(longRef)).thenReturn(Optional.empty());

        ResponseEntity<List<ContentDocumentController.ChunkHitView>> response =
                controller.search(REQUESTER, "electrolysis", null, 10, longRef);

        assertThat(response.getBody()).isEmpty();
        assertThat(response.getHeaders().getFirst(
                ContentDocumentController.EMPTY_CAUSE_HEADER))
                .isEqualTo(SearchEmptyCause.COURSE_REF_UNRESOLVED.name());
        verify(vectors, never()).searchServingEligible(any(), any(), any(), anyInt());
        verify(vectors, never()).diagnoseEmpty(any(), any());
    }
}

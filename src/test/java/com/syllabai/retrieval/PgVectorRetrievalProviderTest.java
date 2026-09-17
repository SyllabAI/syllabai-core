package com.syllabai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.syllabai.curriculum.CurriculumScope;
import com.syllabai.tutor.EvidenceItem;
import com.syllabai.tutor.VectorRetriever;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Arm A adapter (T-C14): chunk evidence maps faithfully into fabric candidates;
 * the delegate's honest empty degradation passes through unchanged (the adapter
 * does not re-rank, filter or widen the scope).
 */
class PgVectorRetrievalProviderTest {

    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");
    private static final CurriculumScope SCOPE = new CurriculumScope(CV_ID, "4CH1-IT", Set.of());
    private static final UUID CHUNK_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID DOC_ROW_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    private final VectorRetriever delegate = mock(VectorRetriever.class);
    private final PgVectorRetrievalProvider provider = new PgVectorRetrievalProvider(delegate);

    @Test
    @DisplayName("provider identity: pgvector, available (degradation is delegate-owned)")
    void identity() {
        assertThat(provider.id()).isEqualTo("pgvector");
        assertThat(provider.available()).isTrue();
    }

    @Test
    @DisplayName("blank query fails closed without calling the delegate")
    void blankQueryFailClosed() {
        assertThat(provider.retrieve(StructuredRetrievalQuery.of("", SCOPE, 5))).isEmpty();
        verifyNoInteractions(delegate);
    }

    @Test
    @DisplayName("null query is rejected")
    void nullQueryRejected() {
        assertThatThrownBy(() -> provider.retrieve(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query");
    }

    @Test
    @DisplayName("chunk evidence maps to a candidate with the chunk id as locator")
    void chunkEvidenceMaps() {
        EvidenceItem item = EvidenceItem.fromChunk(DOC_ROW_ID, "doc-9", 2, CHUNK_ID, 4,
                "MARK_SCHEME", "the statute of limitations for electrons", 3, 3,
                List.of("e1", "e2"), "fake-hashing", 0.42);
        org.mockito.Mockito.when(delegate.retrieve("oxidation", 5, SCOPE))
                .thenReturn(List.of(item));

        List<RetrievalCandidate> candidates =
                provider.retrieve(StructuredRetrievalQuery.of("oxidation", SCOPE, 5));

        assertThat(candidates).hasSize(1);
        RetrievalCandidate c = candidates.get(0);
        assertThat(c.providerId()).isEqualTo("pgvector");
        assertThat(c.evidenceLocator()).isEqualTo(CHUNK_ID.toString());
        assertThat(c.documentRowId()).isEqualTo(DOC_ROW_ID);
        assertThat(c.documentId()).isEqualTo("doc-9");
        assertThat(c.docVersion()).isEqualTo(2);
        assertThat(c.content()).isEqualTo("the statute of limitations for electrons");
        assertThat(c.providerScore()).isEqualTo(0.42); // cosine, native
        assertThat(c.validationStatus()).isNull();
        assertThat(c.embeddingModel()).isNull(); // not carried by EvidenceItem — never invented
        // Identity convention (fabric dedup + portable gold refs): chunk ordinals
        // and provenance travel in metadata — the adapter must not drop them.
        assertThat(c.metadata()).containsEntry("chunk_index", "4");
        assertThat(c.metadata()).containsEntry("document_kind", "MARK_SCHEME");
        assertThat(c.metadata()).containsEntry("page_start", "3");
        assertThat(c.metadata()).containsEntry("page_end", "3");
        assertThat(c.metadata()).containsEntry("element_ids", "e1,e2");
    }

    @Test
    @DisplayName("degraded vector side (empty after honest degradation) yields empty candidates")
    void degradedDelegatePassesThrough() {
        org.mockito.Mockito.when(delegate.retrieve("oxidation", 5, SCOPE))
                .thenReturn(List.of());
        assertThat(provider.retrieve(StructuredRetrievalQuery.of("oxidation", SCOPE, 5)))
                .isEmpty();
    }
}

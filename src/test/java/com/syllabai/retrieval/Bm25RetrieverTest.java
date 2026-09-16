package com.syllabai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.syllabai.content.ChunkHit;
import com.syllabai.content.ChunkLexicalRepository;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentRepository;
import com.syllabai.curriculum.CurriculumScope;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Arm B (T-C13): the lexical provider contract — blank queries fail closed
 * without touching the database, hits map faithfully into fabric candidates
 * (chunk id as the fusion key, native ts_rank_cd as the logging-only score,
 * no claimed validation status), and the query's kinds/limit pass through.
 */
class Bm25RetrieverTest {

    private static final UUID CV_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000004c1");
    private static final CurriculumScope SCOPE = new CurriculumScope(CV_ID, "4CH1-IT", Set.of());
    private static final UUID CHUNK_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000c7");
    private static final UUID DOC_ROW_ID =
            UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final ChunkLexicalRepository lexical = mock(ChunkLexicalRepository.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final Bm25Retriever retriever = new Bm25Retriever(lexical, documents);

    @Test
    @DisplayName("provider identity: bm25, available (V28 column is flyway-owned)")
    void identity() {
        assertThat(retriever.id()).isEqualTo("bm25");
        assertThat(retriever.available()).isTrue();
    }

    @Test
    @DisplayName("blank query fails closed to an empty result before any SQL runs")
    void blankQueryFailClosed() {
        assertThat(retriever.retrieve(StructuredRetrievalQuery.of("", SCOPE, 5))).isEmpty();
        assertThat(retriever.retrieve(StructuredRetrievalQuery.of("   ", SCOPE, 5))).isEmpty();
        verifyNoInteractions(lexical);
    }

    @Test
    @DisplayName("null query is rejected")
    void nullQueryRejected() {
        assertThatThrownBy(() -> retriever.retrieve(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query");
    }

    @Test
    @DisplayName("hits map to candidates: chunk id is the locator, score is native, no invented status")
    void hitsMapToCandidates() {
        ChunkHit hit = new ChunkHit(CHUNK_ID, DOC_ROW_ID, "doc-1", "QUESTION_PAPER", 3,
                "molten lead bromide conducts", 2, 2, List.of(), null, 1.75);
        when(lexical.searchServingEligible("electrolysis", Set.of(), CV_ID, 5)).thenReturn(List.of(hit));
        when(documents.findById(DOC_ROW_ID)).thenReturn(Optional.empty());

        List<RetrievalCandidate> candidates =
                retriever.retrieve(StructuredRetrievalQuery.of("electrolysis", SCOPE, 5));

        assertThat(candidates).hasSize(1);
        RetrievalCandidate c = candidates.get(0);
        assertThat(c.providerId()).isEqualTo("bm25");
        assertThat(c.evidenceLocator()).isEqualTo(CHUNK_ID.toString());
        assertThat(c.documentRowId()).isEqualTo(DOC_ROW_ID);
        assertThat(c.documentId()).isEqualTo("doc-1");
        assertThat(c.docVersion()).isEqualTo(1); // honest default when the row is gone
        assertThat(c.knowledgeNodeId()).isNull();
        assertThat(c.nodeCode()).isNull();
        assertThat(c.content()).isEqualTo("molten lead bromide conducts");
        assertThat(c.providerScore()).isEqualTo(1.75);
        assertThat(c.validationStatus()).isNull(); // never invented
        assertThat(c.embeddingModel()).isNull(); // lexical text arm
        assertThat(c.metadata())
                .containsEntry("chunk_index", "3")
                .containsEntry("document_kind", "QUESTION_PAPER");
    }

    @Test
    @DisplayName("kinds and limit pass through; document version resolved from the repository")
    void passthroughAndVersionResolution() {
        when(lexical.searchServingEligible(anyString(), anySet(), any(UUID.class), any(Integer.class)))
                .thenReturn(List.of());
        Set<Document.Kind> kinds = Set.of(Document.Kind.MARK_SCHEME);

        retriever.retrieve(new StructuredRetrievalQuery("titration", SCOPE, null, null,
                null, kinds, null, null, 7));

        ArgumentCaptor<Set<Document.Kind>> kindsCaptor =
                ArgumentCaptor.forClass(Set.class);
        verify(lexical).searchServingEligible(org.mockito.ArgumentMatchers.eq("titration"),
                kindsCaptor.capture(), org.mockito.ArgumentMatchers.eq(CV_ID),
                org.mockito.ArgumentMatchers.eq(7));
        assertThat(kindsCaptor.getValue()).isEqualTo(kinds);
    }
}

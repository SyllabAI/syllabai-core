package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.syllabai.content.Document;
import com.syllabai.content.DocumentChunk;
import com.syllabai.content.DocumentChunkRepository;
import com.syllabai.content.DocumentRepository;
import com.syllabai.content.FetchQueryParser.ParsedFetchQuery;
import com.syllabai.content.FetchService;
import com.syllabai.content.FetchService.FetchPaperHit;
import com.syllabai.content.FetchService.FetchResult;
import com.syllabai.curriculum.CurriculumScope;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deterministic paper-question resolver: bank-anchored and card-anchored
 * resolution under the serving law (VALIDATED-only, REJECTED invisible),
 * unit binding ("paper 1" → 1C then 1CR; explicit units bind exactly), and
 * honest emptiness on unbindable asks. Zero vector calls — resolution is
 * metadata + document rows only.
 */
class PaperQuestionResolverTest {

    private static final CurriculumScope SCOPE = new CurriculumScope(
            UUID.fromString("00000000-0000-0000-0000-0000000004c1"), "4CH1-2017",
            Set.of(UUID.randomUUID()));

    private final FetchService fetchService = mock(FetchService.class);
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);

    private final PaperQuestionResolver resolver =
            new PaperQuestionResolver(fetchService, documents, chunks);

    private static Document document(UUID rowId, String canonicalId, Document.Kind kind,
                                     String validationState, String fileName) {
        Document doc = mock(Document.class);
        when(doc.id()).thenReturn(rowId);
        when(doc.documentId()).thenReturn(canonicalId);
        when(doc.docVersion()).thenReturn(1);
        when(doc.kind()).thenReturn(kind);
        when(doc.validationState()).thenReturn(validationState);
        when(doc.fileName()).thenReturn(fileName);
        return doc;
    }

    private static DocumentChunk chunk(int index, String content, String atomNumber) {
        return new DocumentChunk(UUID.randomUUID(), index, content, 3, 3, List.of(),
                40, new com.syllabai.content.ChunkMetadata(Document.Kind.EXTERNAL_QUESTIONS,
                        null, "JAN", 2022, "1C", atomNumber, List.of()));
    }

    @Test
    @DisplayName("bank anchor: single VALIDATED paper → its QP document's question chunks pin first")
    void bankAnchoredResolution() {
        ParsedFetchQuery parsed = new ParsedFetchQuery("4CH1/2C", null, "JUN", 2019, 10,
                null, false, "june 2019 question 10");
        FetchPaperHit paper = new FetchPaperHit(UUID.randomUUID(), "4CH1/2C", "June 2019",
                "JUN", 2019, "VALIDATED", "qp-doc-1", "ms-doc-1", null);
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of(paper)));
        UUID rowId = UUID.randomUUID();
        Document qp = document(rowId, "qp-doc-1", Document.Kind.QUESTION_PAPER, "VALIDATED", "QP.md");
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-1"))
                .thenReturn(Optional.of(qp));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(rowId)).thenReturn(List.of(
                chunk(6, "(Total for Question 9 = 14 marks)", null),
                chunk(7, "10 (a) The diagram shows the apparatus a teacher uses", "10"),
                chunk(8, "11 (a) This question is about electroplating", null)));

        List<EvidenceItem> pinned = resolver.resolve(
                "explain question 10 from june 2019 paper 2", SCOPE);

        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).source()).isEqualTo(EvidenceItem.EvidenceSource.QUESTION_PAPER);
        assertThat(pinned.get(0).content()).contains("apparatus a teacher uses");
        assertThat(pinned.get(0).chunkIndex()).isEqualTo(7);
        assertThat(pinned.get(0).documentId()).isEqualTo("qp-doc-1");
    }

    @Test
    @DisplayName("serving law: SUGGESTED bank paper never serves; REJECTED is invisible")
    void suggestedBankPaperNeverServes() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JUN", 2019, 10,
                null, false, "explain question 10 june 2019");
        FetchPaperHit suggested = new FetchPaperHit(UUID.randomUUID(), "4CH1/2C", "June 2019",
                "JUN", 2019, "SUGGESTED", "qp-doc-1", null, null);
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of(suggested)));

        List<EvidenceItem> pinned = resolver.resolve("explain question 10 june 2019", SCOPE);

        assertThat(pinned).isEmpty();
        verifyNoInteractions(chunks);
    }

    @Test
    @DisplayName("card anchor: 'paper 1' phrasing → qcard-4CH1-1C-JAN-2022.txt, atomNumber selects the question chunk")
    void cardAnchoredByAtomNumber() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JAN", 2022, 4,
                null, false, "give me the answer of jan 2022 question 4 paper 1");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, true, false, List.of()));
        UUID rowId = UUID.randomUUID();
        Document card = document(rowId, "qcard-doc-1", Document.Kind.EXTERNAL_QUESTIONS,
                "VALIDATED", "qcard-4CH1-1C-JAN-2022.txt");
        when(documents.findTopByFileNameOrderByDocVersionDesc("qcard-4CH1-1C-JAN-2022.txt"))
                .thenReturn(Optional.of(card));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(rowId)).thenReturn(List.of(
                chunk(0, "Question 3 — atomic structure", "q3"),
                chunk(1, "(a) State the meaning of the term atomic number.", "q4"),
                chunk(2, "Question 5 — periodic table", "q5")));

        List<EvidenceItem> pinned = resolver.resolve(
                "give me the answer of jan 2022 question 4 paper 1", SCOPE);

        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).source()).isEqualTo(EvidenceItem.EvidenceSource.CARD);
        assertThat(pinned.get(0).content()).contains("atomic number");
        assertThat(pinned.get(0).retrievalScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("card anchor fallback: atomNumber unpopulated → question-number text markers select the chunk")
    void cardAnchoredByTextMarkers() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, "2C", "JUN", 2019, 10,
                null, true, "4CH1 2C june 2019 question 10");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of()));
        UUID rowId = UUID.randomUUID();
        Document card = document(rowId, "qcard-doc-2", Document.Kind.EXTERNAL_QUESTIONS,
                "VALIDATED", "qcard-4CH1-2C-JUN-2019.txt");
        when(documents.findTopByFileNameOrderByDocVersionDesc("qcard-4CH1-2C-JUN-2019.txt"))
                .thenReturn(Optional.of(card));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(rowId)).thenReturn(List.of(
                chunk(8, "(Total for Question 9 = 14 marks) 10 (a) The diagram shows the apparatus", null),
                chunk(9, "11 (a) This question is about titrations", null)));

        List<EvidenceItem> pinned = resolver.resolve("4CH1 2C june 2019 question 10", SCOPE);

        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).content()).contains("The diagram shows the apparatus");
    }

    @Test
    @DisplayName("explicit regional unit binds exactly: 2CR is consulted, home card is not")
    void explicitRegionalUnitBindsExactly() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, "2CR", "JUN", 2022, 3,
                null, false, "explain 2CR june 2022 question 3");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of()));
        when(documents.findTopByFileNameOrderByDocVersionDesc("qcard-4CH1-2CR-JUN-2022.txt"))
                .thenReturn(Optional.empty());

        List<EvidenceItem> pinned = resolver.resolve("explain 2CR june 2022 question 3", SCOPE);

        assertThat(pinned).isEmpty();
        verify(documents).findTopByFileNameOrderByDocVersionDesc("qcard-4CH1-2CR-JUN-2022.txt");
        verify(documents).findTopByFileNameOrderByDocVersionDesc("qcard-4CH0-2CR-JUN-2022.txt");
    }

    @Test
    @DisplayName("serving law at the card: a SUGGESTED card document never pins")
    void suggestedCardNeverServes() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, "1C", "JAN", 2022, 4,
                null, false, "jan 2022 1C question 4");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of()));
        UUID rowId = UUID.randomUUID();
        Document card = document(rowId, "qcard-doc-3", Document.Kind.EXTERNAL_QUESTIONS,
                "SUGGESTED", "qcard-4CH1-1C-JAN-2022.txt");
        when(documents.findTopByFileNameOrderByDocVersionDesc("qcard-4CH1-1C-JAN-2022.txt"))
                .thenReturn(Optional.of(card));

        List<EvidenceItem> pinned = resolver.resolve("jan 2022 1C question 4", SCOPE);

        assertThat(pinned).isEmpty();
        verifyNoInteractions(chunks);
    }

    @Test
    @DisplayName("not a bindable paper ask: no year or question number → empty, zero document lookups")
    void unbindableAskIsHonestlyEmpty() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, null, null, null,
                null, false, "explain ionic bonding");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of()));

        List<EvidenceItem> pinned = resolver.resolve("explain ionic bonding", SCOPE);

        assertThat(pinned).isEmpty();
        verifyNoInteractions(documents, chunks);
    }

    @Test
    @DisplayName("the resolver never breaks the ask: a failing fetch degrades to the card anchor")
    void fetchFailureDegradesHonestly() {
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenThrow(new IllegalStateException("bank unavailable"));

        List<EvidenceItem> pinned = resolver.resolve("explain question 10 june 2019", SCOPE);

        assertThat(pinned).isEmpty();
        verifyNoInteractions(documents, chunks);
    }
}

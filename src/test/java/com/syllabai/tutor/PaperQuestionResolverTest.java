package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                        null, "JAN", 2022, "1C", atomNumber, List.<String>of(), 1));
    }

    @Test
    @DisplayName("bank anchor: single VALIDATED paper → its QP document's question chunks pin first "
            + "(branch-1 law: the doc row's SUGGESTED state does not block)")
    void bankAnchoredResolution() {
        ParsedFetchQuery parsed = new ParsedFetchQuery("4CH1/2C", null, "JUN", 2019, 10,
                null, false, "june 2019 question 10");
        FetchPaperHit paper = new FetchPaperHit(UUID.randomUUID(), "4CH1/2C", "June 2019",
                "JUN", 2019, "VALIDATED", "qp-doc-1", "ms-doc-1", null);
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of(paper)));
        UUID rowId = UUID.randomUUID();
        Document qp = document(rowId, "qp-doc-1", Document.Kind.QUESTION_PAPER, "SUGGESTED", "QP.md");
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
    @DisplayName("serving law: a REJECTED document row never pins, even under a VALIDATED paper")
    void rejectedBankDocumentNeverServes() {
        ParsedFetchQuery parsed = new ParsedFetchQuery("4CH1/2C", null, "JUN", 2019, 10,
                null, false, "june 2019 question 10");
        FetchPaperHit paper = new FetchPaperHit(UUID.randomUUID(), "4CH1/2C", "June 2019",
                "JUN", 2019, "VALIDATED", "qp-doc-1", null, null);
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of(paper)));
        Document qp = document(UUID.randomUUID(), "qp-doc-1", Document.Kind.QUESTION_PAPER,
                "REJECTED", "QP.md");
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-1"))
                .thenReturn(Optional.of(qp));

        List<EvidenceItem> pinned = resolver.resolve(
                "explain question 10 from june 2019 paper 2", SCOPE);

        assertThat(pinned).isEmpty();
        // the card + content-store anchors still probe; no chunk row may load
        verify(chunks, never()).findByDocumentRowIdOrderByChunkIndexAsc(
                org.mockito.ArgumentMatchers.any());
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
        // the content-store anchor still probes; no chunk row may load
        verify(chunks, never()).findByDocumentRowIdOrderByChunkIndexAsc(
                org.mockito.ArgumentMatchers.any());
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
        // the content-store anchor still probes (identity bindable); no chunk row may load
        verify(chunks, never()).findByDocumentRowIdOrderByChunkIndexAsc(
                org.mockito.ArgumentMatchers.any());
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

    // ── tier 2b: content-store paper anchor ───────────────────────────────

    @Test
    @DisplayName("content-store companion: the bound identity's real QP/MS chunks pin after the card")
    void contentStoreCompanionPinsQpAndMsAfterCard() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JAN", 2022, 4,
                null, true, "give me the answer of jan 2022 question 4 paper 1");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, true, false, List.of()));
        UUID cardRow = UUID.randomUUID();
        Document card = document(cardRow, "qcard-doc-1", Document.Kind.EXTERNAL_QUESTIONS,
                "VALIDATED", "qcard-4CH1-1C-JAN-2022.txt");
        when(documents.findTopByFileNameOrderByDocVersionDesc("qcard-4CH1-1C-JAN-2022.txt"))
                .thenReturn(Optional.of(card));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(cardRow)).thenReturn(List.of(
                chunk(0, "Question 4 — percentage of oxygen (8 marks)", "q4")));

        UUID qpRow = UUID.randomUUID();
        UUID msRow = UUID.randomUUID();
        when(chunks.findRowIdsByPaperIdentity("JAN", 2022,
                List.of("4CH1/1C", "4CH1/1CR", "4CH0/1C", "4CH0/1CR")))
                .thenReturn(List.<Object[]>of(new Object[]{qpRow, "4CH1/1C"}, new Object[]{msRow, "4CH1/1C"}));
        Document qp = document(qpRow, "qp-doc-9", Document.Kind.QUESTION_PAPER, "VALIDATED", "qp.pdf");
        Document ms = document(msRow, "ms-doc-9", Document.Kind.MARK_SCHEME, "VALIDATED", "ms.pdf");
        when(documents.findById(qpRow)).thenReturn(Optional.of(qp));
        when(documents.findById(msRow)).thenReturn(Optional.of(ms));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-9")).thenReturn(Optional.of(qp));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("ms-doc-9")).thenReturn(Optional.of(ms));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(qpRow)).thenReturn(List.of(
                chunk(3, "Q3 states of matter", "3"),
                chunk(4, "Q4 — percentage of oxygen in a gaseous mixture", "4"),
                chunk(5, "Q4 — suggest one reason why the percentage may not be accurate", "4"),
                chunk(6, "Q5 isotopes", "5")));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(msRow)).thenReturn(List.of(
                chunk(3, "Mark scheme for Question 3", "3"),
                chunk(4, "Q4 P1: heat the copper / Q4 P3: copper(II) oxide", "4"),
                chunk(5, "Q4 P5: leak in the apparatus / (20÷253)×100 = 7.9%", "4"),
                chunk(6, "Mark scheme for Question 5", "5")));

        List<EvidenceItem> pinned = resolver.resolve(
                "give me the answer of jan 2022 question 4 paper 1", SCOPE);

        // mark-scheme-seeking allocation: QP stem shrinks to its first chunk
        // (the card carries marks + a truncated stem), MS keeps both answer chunks
        assertThat(pinned).hasSize(4);
        assertThat(pinned.get(0).source()).isEqualTo(EvidenceItem.EvidenceSource.CARD);
        assertThat(pinned.get(1).source()).isEqualTo(EvidenceItem.EvidenceSource.QUESTION_PAPER);
        assertThat(pinned.get(1).documentId()).isEqualTo("qp-doc-9");
        assertThat(pinned.get(1).chunkIndex()).isEqualTo(4);
        assertThat(pinned.get(2).source()).isEqualTo(EvidenceItem.EvidenceSource.MARK_SCHEME);
        assertThat(pinned.get(2).documentId()).isEqualTo("ms-doc-9");
        assertThat(pinned.get(2).content()).contains("copper(II) oxide");
        assertThat(pinned.get(3).source()).isEqualTo(EvidenceItem.EvidenceSource.MARK_SCHEME);
        assertThat(pinned.get(3).content()).contains("7.9%");
    }

    @Test
    @DisplayName("multi-part mark scheme: a three-part question's answer chunks ALL pin on a "
            + "mark-scheme-seeking ask (QP stem yields its slot; the 2026-10-03 live finding)")
    void multiPartMsPinsAllPartsWhenSeeking() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JAN", 2023, 9,
                null, true, "give me the answer paper 1c january 2023 question 9");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, true, false, List.of()));
        UUID qpRow = UUID.randomUUID();
        UUID msRow = UUID.randomUUID();
        when(chunks.findRowIdsByPaperIdentity("JAN", 2023,
                List.of("4CH1/1C", "4CH1/1CR", "4CH0/1C", "4CH0/1CR")))
                .thenReturn(List.<Object[]>of(new Object[]{qpRow, "4CH1/1C"}, new Object[]{msRow, "4CH1/1C"}));
        Document qp = document(qpRow, "qp-doc-23", Document.Kind.QUESTION_PAPER, "VALIDATED", "qp.pdf");
        Document ms = document(msRow, "ms-doc-23", Document.Kind.MARK_SCHEME, "VALIDATED", "ms.pdf");
        when(documents.findById(qpRow)).thenReturn(Optional.of(qp));
        when(documents.findById(msRow)).thenReturn(Optional.of(ms));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-23")).thenReturn(Optional.of(qp));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("ms-doc-23")).thenReturn(Optional.of(ms));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(qpRow)).thenReturn(List.of(
                chunk(0, "9 (a) Explain the forces of attraction in a covalent bond", "9"),
                chunk(1, "9 (b) Explain the trend in boiling points down Group 7", "9"),
                chunk(2, "9 (c) Explain why graphite is soft and conducts electricity", "9")));
        // three answer chunks — the old flat store cap (2) dropped the third part,
        // leaving it to outcompete front-matter chunks in fusion (and lose)
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(msRow)).thenReturn(List.of(
                chunk(10, "9 | (a) electrostatic attraction between nuclei and shared pairs", "9"),
                chunk(11, "9 | (b) boiling points increase down the group", "9"),
                chunk(12, "9 | (c) layers slide / delocalised electrons conduct", "9")));

        List<EvidenceItem> pinned = resolver.resolve(
                "give me the answer paper 1c january 2023 question 9", SCOPE);

        assertThat(pinned).hasSize(4);   // QP 1 (stem) + MS 3 (all parts) ≤ pool 6
        assertThat(pinned.get(0).source()).isEqualTo(EvidenceItem.EvidenceSource.QUESTION_PAPER);
        assertThat(pinned.get(0).chunkIndex()).isEqualTo(0);
        for (int i = 1; i <= 3; i++) {
            assertThat(pinned.get(i).source()).isEqualTo(EvidenceItem.EvidenceSource.MARK_SCHEME);
            assertThat(pinned.get(i).chunkIndex()).isEqualTo(9 + i);
        }
        assertThat(pinned.get(3).content()).contains("delocalised electrons");
    }

    @Test
    @DisplayName("non-seeking ask keeps the flat allocation: QP ≤2, no MS pins")
    void nonSeekingAskKeepsFlatAllocation() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JUN", 2019, 10,
                null, false, "explain question 10 from june 2019 paper 2");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, true, false, List.of()));
        UUID qpRow = UUID.randomUUID();
        UUID msRow = UUID.randomUUID();
        when(chunks.findRowIdsByPaperIdentity("JUN", 2019,
                List.of("4CH1/1C", "4CH1/1CR", "4CH0/1C", "4CH0/1CR")))
                .thenReturn(List.<Object[]>of(new Object[]{qpRow, "4CH1/1C"}, new Object[]{msRow, "4CH1/1C"}));
        Document qp = document(qpRow, "qp-doc-19", Document.Kind.QUESTION_PAPER, "VALIDATED", "qp.pdf");
        Document ms = document(msRow, "ms-doc-19", Document.Kind.MARK_SCHEME, "VALIDATED", "ms.pdf");
        when(documents.findById(qpRow)).thenReturn(Optional.of(qp));
        when(documents.findById(msRow)).thenReturn(Optional.of(ms));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-19")).thenReturn(Optional.of(qp));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("ms-doc-19")).thenReturn(Optional.of(ms));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(qpRow)).thenReturn(List.of(
                chunk(4, "10 (a) two Q10 stem chunks", "10"),
                chunk(5, "10 (b) the second Q10 stem chunk", "10")));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(msRow)).thenReturn(List.of(
                chunk(9, "10 | (a) an answer row that must NOT pin on a non-seeking ask", "10")));

        List<EvidenceItem> pinned = resolver.resolve(
                "explain question 10 from june 2019 paper 2", SCOPE);

        assertThat(pinned).hasSize(2);   // QP stem both chunks; MS never pins
        assertThat(pinned).allSatisfy(item ->
                assertThat(item.source()).isEqualTo(EvidenceItem.EvidenceSource.QUESTION_PAPER));
        assertThat(pinned.get(0).chunkIndex()).isEqualTo(4);
        assertThat(pinned.get(1).chunkIndex()).isEqualTo(5);
    }

    @Test
    @DisplayName("serving law at the store: a SUGGESTED content-store MS never pins")
    void suggestedContentStoreDocumentNeverPins() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JAN", 2022, 4,
                null, true, "give me the answer of jan 2022 question 4 paper 1");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, true, false, List.of()));
        UUID cardRow = UUID.randomUUID();
        Document card = document(cardRow, "qcard-doc-1", Document.Kind.EXTERNAL_QUESTIONS,
                "VALIDATED", "qcard-4CH1-1C-JAN-2022.txt");
        when(documents.findTopByFileNameOrderByDocVersionDesc("qcard-4CH1-1C-JAN-2022.txt"))
                .thenReturn(Optional.of(card));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(cardRow)).thenReturn(List.of(
                chunk(0, "Question 4 — percentage of oxygen (8 marks)", "q4")));

        UUID qpRow = UUID.randomUUID();
        UUID msRow = UUID.randomUUID();
        when(chunks.findRowIdsByPaperIdentity("JAN", 2022,
                List.of("4CH1/1C", "4CH1/1CR", "4CH0/1C", "4CH0/1CR")))
                .thenReturn(List.<Object[]>of(new Object[]{qpRow, "4CH1/1C"}, new Object[]{msRow, "4CH1/1C"}));
        Document qp = document(qpRow, "qp-doc-9", Document.Kind.QUESTION_PAPER, "VALIDATED", "qp.pdf");
        Document ms = document(msRow, "ms-doc-9", Document.Kind.MARK_SCHEME, "SUGGESTED", "ms.pdf");
        when(documents.findById(qpRow)).thenReturn(Optional.of(qp));
        when(documents.findById(msRow)).thenReturn(Optional.of(ms));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-9")).thenReturn(Optional.of(qp));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(qpRow)).thenReturn(List.of(
                chunk(4, "Q4 — percentage of oxygen in a gaseous mixture", "4"),
                chunk(5, "Q4 — suggest one reason why the percentage may not be accurate", "4")));

        List<EvidenceItem> pinned = resolver.resolve(
                "give me the answer of jan 2022 question 4 paper 1", SCOPE);

        assertThat(pinned).hasSize(3);   // card + 2 QP; the SUGGESTED MS is invisible
        assertThat(pinned.get(0).source()).isEqualTo(EvidenceItem.EvidenceSource.CARD);
        assertThat(pinned.get(1).source()).isEqualTo(EvidenceItem.EvidenceSource.QUESTION_PAPER);
        verify(chunks, never()).findByDocumentRowIdOrderByChunkIndexAsc(msRow);
    }

    @Test
    @DisplayName("a superseded version row never pins — the top version does")
    void staleVersionRowNeverPins() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, "1C", "JAN", 2022, 4,
                null, true, "jan 2022 1C question 4");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of()));
        UUID staleRow = UUID.randomUUID();
        Document stale = document(staleRow, "qp-doc-9", Document.Kind.QUESTION_PAPER,
                "VALIDATED", "qp.pdf");
        Document top = document(UUID.randomUUID(), "qp-doc-9", Document.Kind.QUESTION_PAPER,
                "VALIDATED", "qp.pdf");
        when(chunks.findRowIdsByPaperIdentity("JAN", 2022, List.of("4CH1/1C", "4CH0/1C")))
                .thenReturn(List.<Object[]>of(new Object[]{staleRow, "4CH1/1C"}));
        when(documents.findById(staleRow)).thenReturn(Optional.of(stale));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-9"))
                .thenReturn(Optional.of(top));

        List<EvidenceItem> pinned = resolver.resolve("jan 2022 1C question 4", SCOPE);

        assertThat(pinned).isEmpty();
        verify(chunks, never()).findByDocumentRowIdOrderByChunkIndexAsc(staleRow);
    }

    @Test
    @DisplayName("content-store anchor works without a card — identity alone pins the QP/MS")
    void contentStorePinsWithoutCard() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JAN", 2022, 4,
                null, true, "give me the answer of jan 2022 question 4 paper 1");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, true, false, List.of()));
        when(documents.findTopByFileNameOrderByDocVersionDesc(anyString()))
                .thenReturn(Optional.empty());
        UUID qpRow = UUID.randomUUID();
        UUID msRow = UUID.randomUUID();
        when(chunks.findRowIdsByPaperIdentity("JAN", 2022,
                List.of("4CH1/1C", "4CH1/1CR", "4CH0/1C", "4CH0/1CR")))
                .thenReturn(List.<Object[]>of(new Object[]{qpRow, "4CH1/1C"}, new Object[]{msRow, "4CH1/1C"}));
        Document qp = document(qpRow, "qp-doc-9", Document.Kind.QUESTION_PAPER, "VALIDATED", "qp.pdf");
        Document ms = document(msRow, "ms-doc-9", Document.Kind.MARK_SCHEME, "VALIDATED", "ms.pdf");
        when(documents.findById(qpRow)).thenReturn(Optional.of(qp));
        when(documents.findById(msRow)).thenReturn(Optional.of(ms));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-9")).thenReturn(Optional.of(qp));
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("ms-doc-9")).thenReturn(Optional.of(ms));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(qpRow)).thenReturn(List.of(
                chunk(4, "Q4 — percentage of oxygen in a gaseous mixture", "4"),
                chunk(5, "Q4 — suggest one reason why the percentage may not be accurate", "4")));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(msRow)).thenReturn(List.of(
                chunk(4, "Q4 P1: heat the copper / Q4 P3: copper(II) oxide", "4"),
                chunk(5, "Q4 P5: leak in the apparatus / (20÷253)×100 = 7.9%", "4")));

        List<EvidenceItem> pinned = resolver.resolve(
                "give me the answer of jan 2022 question 4 paper 1", SCOPE);

        assertThat(pinned).hasSize(4);
        assertThat(pinned.get(0).source()).isEqualTo(EvidenceItem.EvidenceSource.QUESTION_PAPER);
        assertThat(pinned.get(2).source()).isEqualTo(EvidenceItem.EvidenceSource.MARK_SCHEME);
    }

    @Test
    @DisplayName("identity miss: no content-store document carries the bound identity → card-only")
    void identityMissLeavesCardOnly() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JAN", 2022, 4,
                null, true, "give me the answer of jan 2022 question 4 paper 1");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, true, false, List.of()));
        UUID cardRow = UUID.randomUUID();
        Document card = document(cardRow, "qcard-doc-1", Document.Kind.EXTERNAL_QUESTIONS,
                "VALIDATED", "qcard-4CH1-1C-JAN-2022.txt");
        when(documents.findTopByFileNameOrderByDocVersionDesc("qcard-4CH1-1C-JAN-2022.txt"))
                .thenReturn(Optional.of(card));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(cardRow)).thenReturn(List.of(
                chunk(0, "Question 4 — percentage of oxygen (8 marks)", "q4")));
        when(chunks.findRowIdsByPaperIdentity("JAN", 2022,
                List.of("4CH1/1C", "4CH1/1CR", "4CH0/1C", "4CH0/1CR")))
                .thenReturn(List.of());

        List<EvidenceItem> pinned = resolver.resolve(
                "give me the answer of jan 2022 question 4 paper 1", SCOPE);

        assertThat(pinned).hasSize(1);
        assertThat(pinned.get(0).source()).isEqualTo(EvidenceItem.EvidenceSource.CARD);
    }

    // ── fail-open verdict (09-27 adjudication, direction (a)) ─────────────

    @Test
    @DisplayName("fail-open verdict: a complete identity that binds nothing reports identityParsed=true "
            + "with the echoed label (the A1 misattribution shape)")
    void completeIdentityWithNoAnchorIsTheGuardVerdict() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JUN", 2019, 10,
                null, false, "explain question 10 june 2019 paper 2");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of()));
        when(documents.findTopByFileNameOrderByDocVersionDesc(anyString()))
                .thenReturn(Optional.empty());
        when(chunks.findRowIdsByPaperIdentity("JUN", 2019,
                List.of("4CH1/2C", "4CH1/2CR", "4CH0/2C", "4CH0/2CR")))
                .thenReturn(List.of());

        PaperQuestionResolver.Resolution resolution = resolver.resolveWithVerdict(
                "explain question 10 june 2019 paper 2", SCOPE);

        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.identityParsed()).isTrue();
        assertThat(resolution.identityLabel())
                .isEqualTo("question 10 from the June 2019 paper 2");
    }

    @Test
    @DisplayName("fail-open verdict: bank ambiguity (question resolves in several SUGGESTED rows) does NOT "
            + "disarm the guard — the 09-27 G1 live shape (JUN-2019 q10 on 1C/1CR)")
    void ambiguousBankRowsStillGateWhenNothingBinds() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JUN", 2019, 10,
                null, false, "explain question 10 june 2019 paper 2");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, true, false, List.of()));
        when(documents.findTopByFileNameOrderByDocVersionDesc(anyString()))
                .thenReturn(Optional.empty());
        when(chunks.findRowIdsByPaperIdentity("JUN", 2019, List.of(
                "4CH1/2C", "4CH1/2CR", "4CH0/2C", "4CH0/2CR")))
                .thenReturn(List.of());

        PaperQuestionResolver.Resolution resolution = resolver.resolveWithVerdict(
                "explain question 10 june 2019 paper 2", SCOPE);

        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.identityParsed()).isTrue();
        assertThat(resolution.identityLabel())
                .isEqualTo("question 10 from the June 2019 paper 2");
    }

    @Test
    @DisplayName("fail-open verdict: an incomplete identity (no series/year) is not a paper ask")
    void incompleteIdentityIsNotAPaperAsk() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, null, null, 10,
                null, false, "explain question 10");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of()));

        PaperQuestionResolver.Resolution resolution = resolver.resolveWithVerdict(
                "explain question 10", SCOPE);

        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.identityParsed()).isFalse();
        verifyNoInteractions(documents, chunks);
    }

    @Test
    @DisplayName("fail-open verdict: a served bank anchor keeps identityParsed=true with its items")
    void bankServedVerdictCarriesItems() {
        ParsedFetchQuery parsed = new ParsedFetchQuery("4CH1/2C", null, "JUN", 2019, 10,
                null, false, "june 2019 question 10");
        FetchPaperHit paper = new FetchPaperHit(UUID.randomUUID(), "4CH1/2C", "June 2019",
                "JUN", 2019, "VALIDATED", "qp-doc-1", null, null);
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of(paper)));
        UUID rowId = UUID.randomUUID();
        Document qp = document(rowId, "qp-doc-1", Document.Kind.QUESTION_PAPER, "SUGGESTED", "QP.md");
        when(documents.findTopByDocumentIdOrderByDocVersionDesc("qp-doc-1"))
                .thenReturn(Optional.of(qp));
        when(chunks.findByDocumentRowIdOrderByChunkIndexAsc(rowId)).thenReturn(List.of(
                chunk(7, "10 (a) The diagram shows the apparatus a teacher uses", "10")));

        PaperQuestionResolver.Resolution resolution = resolver.resolveWithVerdict(
                "explain question 10 from june 2019 paper 2", SCOPE);

        assertThat(resolution.items()).hasSize(1);
        assertThat(resolution.identityParsed()).isTrue();
        assertThat(resolution.identityLabel())
                .isEqualTo("question 10 from the June 2019 paper 2C");
    }

    @Test
    @DisplayName("fail-open verdict: a content-store failure under a parsed identity is fail-closed "
            + "(empty items + identityParsed=true), never the wrong-paper serve")
    void storeFailureUnderParsedIdentityRefuses() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JUN", 2019, 10,
                null, false, "explain question 10 june 2019 paper 2");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of()));
        when(documents.findTopByFileNameOrderByDocVersionDesc(anyString()))
                .thenReturn(Optional.empty());
        when(chunks.findRowIdsByPaperIdentity("JUN", 2019,
                List.of("4CH1/2C", "4CH1/2CR", "4CH0/2C", "4CH0/2CR")))
                .thenThrow(new IllegalStateException("store unavailable"));

        PaperQuestionResolver.Resolution resolution = resolver.resolveWithVerdict(
                "explain question 10 june 2019 paper 2", SCOPE);

        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.identityParsed()).isTrue();
    }

    @Test
    @DisplayName("L3: a mid-resolution card failure under a parsed identity keeps the verdict — "
            + "the guard refuses deterministically, never downgrades to not-a-paper-ask")
    void cardFailureUnderParsedIdentityRefuses() {
        ParsedFetchQuery parsed = new ParsedFetchQuery(null, null, "JUN", 2019, 10,
                null, false, "explain question 10 june 2019 paper 2");
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenReturn(new FetchResult(parsed, false, false, List.of()));
        when(documents.findTopByFileNameOrderByDocVersionDesc(anyString()))
                .thenThrow(new IllegalStateException("documents unavailable"));

        PaperQuestionResolver.Resolution resolution = resolver.resolveWithVerdict(
                "explain question 10 june 2019 paper 2", SCOPE);

        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.identityParsed()).isTrue();
        assertThat(resolution.identityLabel()).contains("question 10");
    }

    @Test
    @DisplayName("L3: a fetch-tier failure under a complete identity recovers the verdict by "
            + "local re-parse — identityParsed=true, the fail-open guard fires")
    void fetchFailureUnderParsedIdentityRefuses() {
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenThrow(new IllegalStateException("bank unavailable"));

        PaperQuestionResolver.Resolution resolution = resolver.resolveWithVerdict(
                "explain question 10 from june 2019 paper 2", SCOPE);

        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.identityParsed()).isTrue();
        assertThat(resolution.identityLabel()).contains("question 10");
    }

    @Test
    @DisplayName("L3: a fetch-tier failure under an INCOMPLETE identity keeps the honest fallback — "
            + "notPaperAsk, the vector+KG path serves")
    void fetchFailureUnderPartialIdentityFallsBack() {
        when(fetchService.fetch(anyString(), org.mockito.ArgumentMatchers.eq(SCOPE)))
                .thenThrow(new IllegalStateException("bank unavailable"));

        PaperQuestionResolver.Resolution resolution = resolver.resolveWithVerdict(
                "explain ionic bonding", SCOPE);

        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.identityParsed()).isFalse();
    }
}

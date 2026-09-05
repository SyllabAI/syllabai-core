package com.syllabai.teacher.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkPoint;
import com.syllabai.assessment.MarkPointRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.content.ContentIngestionService;
import com.syllabai.content.Document;
import com.syllabai.shared.ConflictException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-C02 bridge orchestration with the REAL three-pair fixtures: canonical
 * documents flow through the existing T-013 service (checksum idempotency),
 * assessment content flows through the existing T-011 service (all SUGGESTED),
 * the parser contract is persisted verbatim in the bridge record, reruns
 * resolve to the existing record without re-ingesting, and embedding is never
 * touched (the bridge does not even hold a reference to the embedding service).
 */
class GlmOcrIngestionServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES = Path.of("src/test/resources/fixtures/glm-ocr");

    private final ContentIngestionService contentIngestion = mock(ContentIngestionService.class);
    private final PastPaperIngestionService pastPaperIngestion =
            mock(PastPaperIngestionService.class);
    private final GlmOcrBridgeRecordRepository bridgeRecords =
            mock(GlmOcrBridgeRecordRepository.class);
    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionVersionRepository questionVersions =
            mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final MarkPointRepository markPoints = mock(MarkPointRepository.class);

    private final GlmOcrIngestionService service = new GlmOcrIngestionService(
            contentIngestion, pastPaperIngestion, new GlmOcrDraftMapper(), bridgeRecords,
            examPapers, questionVersions, markSchemes, markPoints);

    private static final UUID OPERATOR = UUID.randomUUID();

    private GlmOcrIngestionService.GlmOcrPairRequest octoberPair() throws Exception {
        return pair("october-2025-wph11-01");
    }

    private static GlmOcrIngestionService.GlmOcrPairRequest pair(String name) throws Exception {
        String qpJson = Files.readString(FIXTURES.resolve(name + "/qp-canonical.json"));
        String msJson = Files.readString(FIXTURES.resolve(name + "/ms-canonical.json"));
        return new GlmOcrIngestionService.GlmOcrPairRequest(
                JSON.readValue(qpJson, CanonicalDocumentDto.class), qpJson,
                JSON.readValue(msJson, CanonicalDocumentDto.class), msJson,
                JSON.readValue(Files.readString(FIXTURES.resolve(name + "/qp-draft.json")).getBytes(),
                        GlmOcrPaperDraftDto.class),
                JSON.readValue(Files.readString(FIXTURES.resolve(name + "/ms-draft.json")).getBytes(),
                        GlmOcrMarkSchemeDraftDto.class),
                JSON.readValue(Files.readString(FIXTURES.resolve(name + "/reconciliation.json")).getBytes(),
                        GlmOcrReconciliationDto.class));
    }

    @BeforeEach
    void wireMocks() {
        when(contentIngestion.ingest(any(), any(), any(), any())).thenAnswer(inv -> {
            CanonicalDocumentDto doc = inv.getArgument(0);
            if (doc == null) {
                return null; // Mockito's stubbing probe call
            }
            return new ContentIngestionService.IngestionResult(
                    UUID.randomUUID(), doc.documentId(), false, 12, 40, 1, 12);
        });
        when(bridgeRecords.findByQpDocumentIdAndMsDocumentId(any(), any()))
                .thenReturn(Optional.empty());
        when(pastPaperIngestion.ingest(any(), any())).thenAnswer(inv -> {
            PastPaperDraftDto draft = inv.getArgument(0);
            if (draft == null) {
                return null; // Mockito's stubbing probe call
            }
            int questions = draft.questions().size();
            int parts = draft.questions().stream().mapToInt(q -> q.parts().size()).sum();
            int points = draft.markScheme().points().size();
            return new PastPaperIngestionService.IngestionSummary(
                    UUID.randomUUID(), questions, parts, points);
        });
        when(bridgeRecords.save(any(GlmOcrBridgeRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(examPapers.findById(any())).thenReturn(Optional.empty());
        when(questionVersions.findByPaperId(any())).thenReturn(List.of());
        when(markSchemes.findByPaperId(any())).thenReturn(List.of());
        when(markPoints.findByMarkSchemeIdOrderByOrdering(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("happy path: T-013 ingest with the right kinds + raw JSON, T-011 ingest once, record saved")
    void ingestsThroughExistingServices() throws Exception {
        GlmOcrIngestionService.GlmOcrPairRequest pair = octoberPair();
        UUID paperId = UUID.randomUUID();
        when(pastPaperIngestion.ingest(any(), any())).thenReturn(
                new PastPaperIngestionService.IngestionSummary(paperId, 20, 26, 53));
        when(markSchemes.findByPaperId(paperId)).thenReturn(List.of(new MarkScheme(
                null, "1", pair.msDraft().paper().canonicalDocumentId(), "glm-ocr-ms-v1")));
        when(examPapers.findById(paperId)).thenReturn(Optional.of(new ExamPaper(
                UUID.randomUUID(), "IAL WPH11/01 October 2025", "Edexcel", "IAL", null,
                "October 2025", "WPH11/01", pair.qpCanonical().documentId(),
                pair.msCanonical().documentId(), ExamPaper.Provenance.PAST_PAPER,
                "glm-ocr-qp-v1+glm-ocr-ms-v1", OPERATOR)));

        GlmOcrIngestionService.PairResult result = service.ingestPair(pair, OPERATOR);

        // canonical documents went through the EXISTING T-013 service, raw JSON verbatim
        verify(contentIngestion).ingest(eq(pair.qpCanonical()), eq(pair.qpCanonicalJson()),
                eq(Document.Kind.QUESTION_PAPER), eq(OPERATOR));
        verify(contentIngestion).ingest(eq(pair.msCanonical()), eq(pair.msCanonicalJson()),
                eq(Document.Kind.MARK_SCHEME), eq(OPERATOR));

        // assessment content went through the EXISTING T-011 path, exactly once
        verify(pastPaperIngestion).ingest(any(PastPaperDraftDto.class), eq(OPERATOR));

        // the bridge record persists the verbatim parser contract
        org.mockito.ArgumentCaptor<GlmOcrBridgeRecord> captor =
                org.mockito.ArgumentCaptor.forClass(GlmOcrBridgeRecord.class);
        verify(bridgeRecords).save(captor.capture());
        GlmOcrBridgeRecord record = captor.getValue();
        assertThat(record.paperId()).isEqualTo(paperId);
        assertThat(record.qpDocumentId()).isEqualTo(pair.qpCanonical().documentId());
        assertThat(record.msDocumentId()).isEqualTo(pair.msCanonical().documentId());
        assertThat(record.extractionMethods()).isEqualTo("glm-ocr-qp-v1+glm-ocr-ms-v1");
        assertThat(record.reconciliationStatus()).isEqualTo("OK"); // October: no paper-total conflict
        assertThat(record.qpDraft()).contains("glm-ocr-qp-v1");
        assertThat(record.msDraft()).contains("P78831A");
        assertThat(record.reconciliation()).contains("80");

        assertThat(result.qpDocument().duplicate()).isFalse();
        assertThat(result.msDocument().duplicate()).isFalse();
        assertThat(result.examPaper().duplicate()).isFalse();
        assertThat(result.examPaper().paperId()).isEqualTo(paperId);
        assertThat(result.questions()).isEqualTo(20);
        assertThat(result.parts()).isEqualTo(26);
        assertThat(result.markPoints()).isEqualTo(53);
        assertThat(result.qpChunks()).isEqualTo(12);
        assertThat(result.msChunks()).isEqualTo(12);
        assertThat(result.reconciliation().status()).isEqualTo("OK");
        assertThat(result.embeddingSkipped()).isTrue();
    }

    @Test
    @DisplayName("1A pair: 80-vs-120 conflict → record REVIEW_REQUIRED + findings preserved")
    void conflictPairMarkedReviewRequired() throws Exception {
        GlmOcrIngestionService.GlmOcrPairRequest pair = pair("october-2025-wph11-01a");

        GlmOcrIngestionService.PairResult result = service.ingestPair(pair, OPERATOR);

        org.mockito.ArgumentCaptor<GlmOcrBridgeRecord> captor =
                org.mockito.ArgumentCaptor.forClass(GlmOcrBridgeRecord.class);
        verify(bridgeRecords).save(captor.capture());
        assertThat(captor.getValue().reconciliationStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.reconciliation().paperTotalConflict()).isTrue();
        assertThat(result.reconciliation().qpPaperTotal()).isEqualTo(80);
        assertThat(result.reconciliation().msPaperTotal()).isEqualTo(120);

        // findings: reconciliation + Q18-style warnings + paper-total conflict — nothing dropped
        assertThat(result.reviewFindings().stream()
                .filter(f -> "paper-total-conflict".equals(f.severity()))).hasSize(1);
        assertThat(result.reviewFindings().stream()
                .filter(f -> f.source().equals("RECONCILIATION")
                        && !"paper-total-conflict".equals(f.severity())))
                .hasSize(pair.reconciliation().findings().size());
        // the stored findings JSON round-trips through the review surface
        // (present Optional — the record exists, and its findings list equals
        // the ingested result's, whether empty or not)
        when(bridgeRecords.findByPaperId(result.examPaper().paperId()))
                .thenReturn(Optional.of(captor.getValue()));
        assertThat(service.reviewFindingsForPaper(result.examPaper().paperId()))
                .contains(result.reviewFindings());
    }

    @Test
    @DisplayName("review surface: an existing record with empty findings is present-empty, not missing")
    void emptyFindingsArePresentNotMissing() {
        // a valid bridge record whose review_findings JSONB is "[]" — a clean
        // pair (no reconciliation conflicts, no parser warnings). The service
        // must distinguish this existing record from a missing one.
        UUID paperId = UUID.randomUUID();
        GlmOcrBridgeRecord cleanRecord = new GlmOcrBridgeRecord(
                paperId, "qp-doc-id", "ms-doc-id",
                UUID.randomUUID(), UUID.randomUUID(),
                "qp-checksum", "ms-checksum",
                "glm-ocr-qp-v1+glm-ocr-ms-v1", "OK",
                "[]", "{}", "{}", "{}", null);
        when(bridgeRecords.findByPaperId(paperId)).thenReturn(Optional.of(cleanRecord));

        assertThat(service.reviewFindingsForPaper(paperId)).isPresent();
        assertThat(service.reviewFindingsForPaper(paperId)).contains(List.of());
    }

    @Test
    @DisplayName("review surface: unknown paper (no bridge record) is an empty Optional")
    void missingRecordIsEmptyOptional() {
        UUID unknown = UUID.randomUUID();
        when(bridgeRecords.findByPaperId(unknown)).thenReturn(Optional.empty());

        assertThat(service.reviewFindingsForPaper(unknown)).isEmpty();
    }

    @Test
    @DisplayName("rerun: existing bridge record → nothing re-ingested, everything DUPLICATE")
    void rerunResolvesToExistingRecord() throws Exception {
        GlmOcrIngestionService.GlmOcrPairRequest pair = octoberPair();
        UUID paperId = UUID.randomUUID();
        // rerun: the T-013 service reports the existing rows as duplicates
        when(contentIngestion.ingest(any(), any(), any(), any())).thenAnswer(inv -> {
            CanonicalDocumentDto doc = inv.getArgument(0);
            if (doc == null) {
                return null;
            }
            return new ContentIngestionService.IngestionResult(
                    UUID.randomUUID(), doc.documentId(), true, 12, 40, 1, 12);
        });
        when(bridgeRecords.findByQpDocumentIdAndMsDocumentId(
                pair.qpCanonical().documentId(), pair.msCanonical().documentId()))
                .thenReturn(Optional.of(new GlmOcrBridgeRecord(
                        paperId,
                        pair.qpCanonical().documentId(), pair.msCanonical().documentId(),
                        UUID.randomUUID(), UUID.randomUUID(),
                        "qp-checksum", "ms-checksum",
                        "glm-ocr-qp-v1+glm-ocr-ms-v1", "REVIEW_REQUIRED",
                        JSON.writeValueAsString(new GlmOcrDraftMapper().assembleReviewFindings(
                                pair.qpDraft(), pair.msDraft(), pair.reconciliation())),
                        JSON.writeValueAsString(pair.qpDraft()),
                        JSON.writeValueAsString(pair.msDraft()),
                        JSON.writeValueAsString(pair.reconciliation()),
                        OPERATOR)));
        when(questionVersions.findByPaperId(paperId)).thenReturn(List.of(
                new QuestionVersion(null, 1, "stem", 5, 3, 450, null,
                        QuestionVersion.ValidationState.SUGGESTED, "doc", 0.8, "glm-ocr-qp-v1")));
        when(markSchemes.findByPaperId(paperId)).thenReturn(List.of(new MarkScheme(
                null, "1", pair.msDraft().paper().canonicalDocumentId(), "glm-ocr-ms-v1")));
        when(markPoints.findByMarkSchemeIdOrderByOrdering(any())).thenReturn(List.of(
                new MarkPoint(null, null, "1-a", 0, "text", 1, List.of(), 0.75)));
        when(examPapers.findById(paperId)).thenReturn(Optional.of(new ExamPaper(
                UUID.randomUUID(), "IAL WPH11/01 October 2025", "Edexcel", "IAL", null,
                "October 2025", "WPH11/01", null, null,
                ExamPaper.Provenance.PAST_PAPER, "glm-ocr-qp-v1+glm-ocr-ms-v1", OPERATOR)));

        GlmOcrIngestionService.PairResult result = service.ingestPair(pair, OPERATOR);

        // T-011 never runs again; no second bridge record
        verify(pastPaperIngestion, never()).ingest(any(), any());
        verify(bridgeRecords, never()).save(any());

        assertThat(result.examPaper().duplicate()).isTrue();
        assertThat(result.qpDocument().duplicate()).isTrue();
        assertThat(result.msDocument().duplicate()).isTrue();
        assertThat(result.examPaper().paperId()).isEqualTo(paperId);
        assertThat(result.questions()).isEqualTo(1);
        assertThat(result.parts()).isZero();
        assertThat(result.markSchemes()).isEqualTo(1);
        assertThat(result.markPoints()).isEqualTo(1);
        assertThat(result.embeddingSkipped()).isTrue();
        // rerun reports the state AS IMPORTED (record), not the incoming bundle
        assertThat(result.reconciliation().status()).isEqualTo("REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("fail loud: a QP draft from a different document is rejected, never guessed")
    void mixedBundleRejected() throws Exception {
        GlmOcrIngestionService.GlmOcrPairRequest pair = octoberPair();
        GlmOcrPaperDraftDto wrongDraft = new GlmOcrPaperDraftDto(
                pair.qpDraft().schemaVersion(), pair.qpDraft().extractionMethod(),
                pair.qpDraft().reviewRequired(),
                new GlmOcrPaperDraftDto.PaperMeta(null, null, null, null, null, null, null,
                        null, null, "00000000-0000-0000-0000-000000000000"),
                pair.qpDraft().questions(), pair.qpDraft().questionTotals(),
                pair.qpDraft().paperTotal(), pair.qpDraft().sectionTotals(),
                pair.qpDraft().frontMatterFigures(), pair.qpDraft().warnings());
        GlmOcrIngestionService.GlmOcrPairRequest mixed = new GlmOcrIngestionService.GlmOcrPairRequest(
                pair.qpCanonical(), pair.qpCanonicalJson(), pair.msCanonical(),
                pair.msCanonicalJson(), wrongDraft, pair.msDraft(), pair.reconciliation());

        assertThatThrownBy(() -> service.ingestPair(mixed, OPERATOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("mixes documents from different sources");
        verify(contentIngestion, never()).ingest(any(), any(), any(), any());
        verify(pastPaperIngestion, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("fail loud: a reconciliation from a different pair is rejected")
    void foreignReconciliationRejected() throws Exception {
        GlmOcrIngestionService.GlmOcrPairRequest pair = octoberPair();
        // 1A's 80-vs-120 reconciliation attached to the October pair
        GlmOcrReconciliationDto foreign = pair("october-2025-wph11-01a").reconciliation();
        GlmOcrIngestionService.GlmOcrPairRequest mixed = new GlmOcrIngestionService.GlmOcrPairRequest(
                pair.qpCanonical(), pair.qpCanonicalJson(), pair.msCanonical(),
                pair.msCanonicalJson(), pair.qpDraft(), pair.msDraft(), foreign);

        assertThatThrownBy(() -> service.ingestPair(mixed, OPERATOR))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("is this reconciliation for this pair?");
    }
}

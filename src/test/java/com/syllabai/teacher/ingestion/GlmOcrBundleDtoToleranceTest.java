package com.syllabai.teacher.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.content.CanonicalDocumentDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Forward-compatible bundle contract (hardening round 2, 2026-09; extended
 * s147, 2026-10-01): the parser's bundle schema must be able to EVOLVE
 * (e.g. the MS draft gained {@code figureRefs}; a future reconciliation may
 * carry an asset ledger) without an old bridge failing batch ingestion with
 * {@code UnrecognizedPropertyException} on fields it does not know.
 *
 * <p>Unknown properties are ignored at the DTO boundary — the bridge still
 * fails loudly on CONTENT it cannot accept (schema version, reconciliation
 * conflicts, duplicate papers); those gates are unchanged. All bundle-facing
 * DTO records are annotated {@code @JsonIgnoreProperties(ignoreUnknown=true)},
 * outer records AND nested records (Jackson applies the annotation
 * per-class, never inherited by nested types).</p>
 *
 * <p>s147 extensions pinned here: (a) {@code figureRefs} is a TYPED field of
 * the MS DTO — the bridge persists DTO re-serialization as the verbatim
 * draft record, so a parser-emitted field must round-trip, not be tolerated
 * into oblivion; (b) embedding-v2's {@code RetrievalMeta} and the element
 * {@code group_key} tolerate unknowns like every other nested record;
 * (c) the older T-011/curriculum contracts ({@code past-paper-draft.json},
 * {@code curriculum-draft.json}) carry the same annotation set — they are
 * the same §27 cross-process family.</p>
 */
class GlmOcrBundleDtoToleranceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("ms-draft.json with a figureRefs field (and unknown extras) deserializes")
    void msDraftWithFigureRefs() throws Exception {
        String json = """
                {
                  "schemaVersion": "1.0",
                  "extractionMethod": "glm-ocr-ms-v1",
                  "reviewRequired": true,
                  "paper": null,
                  "entries": [],
                  "questionTotals": {},
                  "paperTotal": 2,
                  "icTable": null,
                  "warnings": ["2 figure reference(s) in the mark-scheme markdown"],
                  "figureRefs": [
                    {
                      "elementId": "el-1",
                      "sourceName": "assets/crop_ms_graph.png",
                      "format": "png",
                      "url": "assets/crop_ms_graph.png",
                      "availability": "unavailable-signed-url"
                    }
                  ],
                  "someFutureField": {"nested": true}
                }
                """;
        GlmOcrMarkSchemeDraftDto dto = JSON.readValue(json, GlmOcrMarkSchemeDraftDto.class);
        assertThat(dto.entries()).isEmpty();
        assertThat(dto.warnings()).hasSize(1);
    }

    @Test
    @DisplayName("ms-draft.json figureRefs is TYPED (enrichment fields included) and round-trips")
    void msDraftFigureRefsTypedAndPersisted() throws Exception {
        // parser-main output shape: figureRefs with local-asset enrichment
        // (b837fa0). Tolerating this as an unknown would DROP it from the
        // bridge record — the bridge stores DTO re-serialization as the
        // verbatim draft, so the field must round-trip.
        String json = """
                {
                  "schemaVersion": "1.0",
                  "extractionMethod": "glm-ocr-ms-v1",
                  "reviewRequired": true,
                  "paper": null,
                  "entries": [],
                  "questionTotals": {},
                  "paperTotal": 2,
                  "icTable": null,
                  "figureRefs": [
                    {
                      "elementId": "el-9",
                      "sourceName": "assets/crop_ms_graph.png",
                      "format": "png",
                      "url": "assets/crop_ms_graph.png",
                      "availability": "available",
                      "assetId": "img:0f2a",
                      "sha256": "0f2a…",
                      "mimeType": "image/png",
                      "width": 640,
                      "height": 480,
                      "formatMismatch": false
                    }
                  ],
                  "warnings": []
                }
                """;
        GlmOcrMarkSchemeDraftDto dto = JSON.readValue(json, GlmOcrMarkSchemeDraftDto.class);
        assertThat(dto.figureRefs()).hasSize(1);
        GlmOcrPaperDraftDto.FigureRef ref = dto.figureRefs().get(0);
        assertThat(ref.assetId()).isEqualTo("img:0f2a");
        assertThat(ref.width()).isEqualTo(640);
        assertThat(ref.formatMismatch()).isFalse();

        // the bridge-record path: re-serialization must KEEP the field
        String reserialized = JSON.writeValueAsString(dto);
        GlmOcrMarkSchemeDraftDto reread = JSON.readValue(reserialized, GlmOcrMarkSchemeDraftDto.class);
        assertThat(reread.figureRefs()).hasSize(1);
        assertThat(reread.figureRefs().get(0).assetId()).isEqualTo("img:0f2a");
    }

    @Test
    @DisplayName("pre-figureRefs engine output (no field) still deserializes — null, never fabricated")
    void msDraftFromPreFigureRefsEngine() throws Exception {
        String json = """
                {
                  "schemaVersion": "1.0",
                  "extractionMethod": "glm-ocr-ms-v1",
                  "reviewRequired": true,
                  "paper": null,
                  "entries": [],
                  "questionTotals": {},
                  "paperTotal": 2,
                  "icTable": null,
                  "warnings": []
                }
                """;
        GlmOcrMarkSchemeDraftDto dto = JSON.readValue(json, GlmOcrMarkSchemeDraftDto.class);
        assertThat(dto.figureRefs()).isNull();
    }

    @Test
    @DisplayName("reconciliation.json with an unknown asset-summary field deserializes")
    void reconciliationWithAssetSummary() throws Exception {
        String json = """
                {
                  "findings": [
                    {"questionNumber": "1", "qpMarks": 2, "msMarks": 2, "severity": "match"}
                  ],
                  "qpPaperTotal": 2,
                  "msPaperTotal": 2,
                  "paperTotalConflict": false,
                  "mismatchCount": 0,
                  "assetSummary": {"referencesTotal": 2, "referencesResolved": 1}
                }
                """;
        GlmOcrReconciliationDto dto = JSON.readValue(json, GlmOcrReconciliationDto.class);
        assertThat(dto.mismatchCount()).isZero();
        assertThat(dto.findings()).hasSize(1);
    }

    @Test
    @DisplayName("qp-draft.json with unknown question-level fields deserializes")
    void qpDraftWithUnknownFields() throws Exception {
        String json = """
                {
                  "schemaVersion": "1.0",
                  "extractionMethod": "glm-ocr-qp-v1",
                  "reviewRequired": true,
                  "paper": null,
                  "questions": [],
                  "questionTotals": {},
                  "paperTotal": 2,
                  "sectionTotals": {},
                  "frontMatterFigures": [],
                  "warnings": [],
                  "futureQuestionMetadata": "anything"
                }
                """;
        GlmOcrPaperDraftDto dto = JSON.readValue(json, GlmOcrPaperDraftDto.class);
        assertThat(dto.questions()).isEmpty();
        assertThat(dto.extractionMethod()).isEqualTo("glm-ocr-qp-v1");
    }

    @Test
    @DisplayName("canonical document with unknown element-level fields deserializes")
    void canonicalDocumentWithUnknownElementFields() throws Exception {
        String json = """
                {
                  "documentId": "doc-1",
                  "schemaVersion": "1.0",
                  "version": 1,
                  "source": {
                    "uri": "corpus/x.md",
                    "checksum": "abc",
                    "checksumAlgorithm": "SHA-256",
                    "mimeType": "text/markdown",
                    "fileName": "x.md",
                    "futureSourceField": 7
                  },
                  "pageCount": null,
                  "pages": [],
                  "sections": [],
                  "textBlocks": [],
                  "tables": [],
                  "figures": [],
                  "equations": [],
                  "provenance": null
                }
                """;
        CanonicalDocumentDto dto = JSON.readValue(json, CanonicalDocumentDto.class);
        assertThat(dto.documentId()).isEqualTo("doc-1");
        assertThat(dto.source().fileName()).isEqualTo("x.md");
    }

    @Test
    @DisplayName("canonical document with embedding-v2 retrieval meta (and its unknowns) deserializes")
    void canonicalDocumentWithRetrievalMeta() throws Exception {
        String json = """
                {
                  "documentId": "doc-2",
                  "schemaVersion": "1.0",
                  "version": 1,
                  "source": {
                    "uri": "corpus/qp.md",
                    "checksum": "abc",
                    "checksumAlgorithm": "SHA-256",
                    "mimeType": "text/markdown",
                    "fileName": "qp.md"
                  },
                  "pageCount": null,
                  "pages": [],
                  "sections": [],
                  "textBlocks": [
                    {
                      "element_id": "tb-1",
                      "element_type": "text",
                      "page_number": 1,
                      "bounding_box": null,
                      "text": "answer",
                      "reading_order": 1,
                      "confidence": 0.9,
                      "role": null,
                      "heading_level": null,
                      "source_engine": "glm-ocr",
                      "source_engine_version": "1",
                      "group_key": "q3",
                      "tomorrowField": true
                    }
                  ],
                  "tables": [],
                  "figures": [],
                  "equations": [],
                  "provenance": null,
                  "retrieval": {
                    "subjectTitle": "Chemistry",
                    "subjectCode": "4CH1",
                    "series": "JAN",
                    "year": 2020,
                    "paperCode": "4CH1/01",
                    "label": null,
                    "unit": null,
                    "specCodes": ["4CH1-1.1"],
                    "futureRetrievalField": 42
                  }
                }
                """;
        CanonicalDocumentDto dto = JSON.readValue(json, CanonicalDocumentDto.class);
        assertThat(dto.retrieval()).isNotNull();
        assertThat(dto.retrieval().series()).isEqualTo("JAN");
        assertThat(dto.retrieval().specCodes()).containsExactly("4CH1-1.1");
        assertThat(dto.textBlocks().get(0).groupKey()).isEqualTo("q3");
    }

    @Test
    @DisplayName("past-paper-draft.json with unknown fields deserializes (same §27 family)")
    void pastPaperDraftWithUnknownFields() throws Exception {
        String json = """
                {
                  "schemaVersion": "1.0",
                  "paper": {
                    "board": "Edexcel",
                    "qualification": "IGCSE",
                    "subject": "Chemistry",
                    "unit": null,
                    "sessionLabel": "January 2012",
                    "paperCode": "4CH1/01",
                    "questionPaperDocumentId": null,
                    "markSchemeDocumentId": null,
                    "futurePaperMetaField": "x"
                  },
                  "questions": [],
                  "markScheme": null,
                  "extractionMethod": "glm-ocr-v0",
                  "reviewRequired": true,
                  "futureTopLevelField": 1
                }
                """;
        PastPaperDraftDto dto = JSON.readValue(json, PastPaperDraftDto.class);
        assertThat(dto.paper().paperCode()).isEqualTo("4CH1/01");
        assertThat(dto.reviewRequired()).isTrue();
    }

    @Test
    @DisplayName("curriculum-draft.json with unknown fields deserializes (same §27 family)")
    void curriculumDraftWithUnknownFields() throws Exception {
        String json = """
                {
                  "schemaVersion": "1.1",
                  "board": "Edexcel",
                  "qualification": "IAL",
                  "code": "YCH11",
                  "title": "Chemistry",
                  "subject": {
                    "code": "YCH11",
                    "name": "Chemistry",
                    "futureSubjectField": true
                  },
                  "units": [],
                  "provenance": null,
                  "futureTopLevelField": 1
                }
                """;
        CurriculumDraftDto dto = JSON.readValue(json, CurriculumDraftDto.class);
        assertThat(dto.subject().name()).isEqualTo("Chemistry");
    }
}

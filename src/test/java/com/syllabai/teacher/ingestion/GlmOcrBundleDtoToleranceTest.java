package com.syllabai.teacher.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syllabai.content.CanonicalDocumentDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Forward-compatible bundle contract (hardening round 2, 2026-09): the
 * parser's bundle schema must be able to EVOLVE (e.g. the MS draft gained
 * {@code figureRefs}; a future reconciliation may carry an asset ledger)
 * without an old bridge failing batch ingestion with
 * {@code UnrecognizedPropertyException} on fields it does not know.
 *
 * <p>Unknown properties are ignored at the DTO boundary — the bridge still
 * fails loudly on CONTENT it cannot accept (schema version, reconciliation
 * conflicts, duplicate papers); those gates are unchanged. All bundle-facing
 * DTO records are annotated {@code @JsonIgnoreProperties(ignoreUnknown=true)},
 * outer records AND nested records (Jackson applies the annotation
 * per-class, never inherited by nested types).</p>
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
}

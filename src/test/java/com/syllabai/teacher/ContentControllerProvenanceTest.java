package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecord;
import com.syllabai.teacher.ingestion.GlmOcrBridgeRecordRepository;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provenance read-model: the ingestion-time source identity (deterministic
 * parser document ids + the source checksums that pin the original QP/MS files)
 * is exposed to teachers read-only, fail-closed when a paper has no bridge
 * record, and never carries draft/reconciliation payloads.
 */
class ContentControllerProvenanceTest {

    private final PastPaperIngestionService ingestion = mock(PastPaperIngestionService.class);
    private final ContentReviewService review = mock(ContentReviewService.class);
    private final ExamPaperRepository examPapers = mock(ExamPaperRepository.class);
    private final QuestionVersionRepository questionVersions = mock(QuestionVersionRepository.class);
    private final MarkSchemeRepository markSchemes = mock(MarkSchemeRepository.class);
    private final GlmOcrBridgeRecordRepository bridgeRecords = mock(GlmOcrBridgeRecordRepository.class);
    private final ContentController controller = new ContentController(
            ingestion, review, examPapers, questionVersions, markSchemes, bridgeRecords);

    @Test
    @DisplayName("provenance view exposes document identities + source checksums, nothing else")
    void provenanceView() {
        UUID paperId = UUID.randomUUID();
        GlmOcrBridgeRecord record = new GlmOcrBridgeRecord(
                paperId, "qp-doc-id", "ms-doc-id", UUID.randomUUID(), UUID.randomUUID(),
                "qp-checksum-sha256", "ms-checksum-sha256",
                "glm-ocr-qp-v1+glm-ocr-ms-v1", "OK",
                "[]", "{}", "{}", "{}", UUID.randomUUID());
        when(bridgeRecords.findByPaperId(paperId)).thenReturn(Optional.of(record));

        ContentController.PaperProvenanceView view = controller.paperProvenance(paperId);

        assertThat(view.paperId()).isEqualTo(paperId);
        assertThat(view.qpDocumentId()).isEqualTo("qp-doc-id");
        assertThat(view.msDocumentId()).isEqualTo("ms-doc-id");
        assertThat(view.qpChecksum()).isEqualTo("qp-checksum-sha256");
        assertThat(view.msChecksum()).isEqualTo("ms-checksum-sha256");
        assertThat(view.extractionMethods()).isEqualTo("glm-ocr-qp-v1+glm-ocr-ms-v1");
        assertThat(view.reconciliationStatus()).isEqualTo("OK");
    }

    @Test
    @DisplayName("fail-closed: a paper without a bridge record has no provenance (404)")
    void provenanceMissing() {
        UUID paperId = UUID.randomUUID();
        when(bridgeRecords.findByPaperId(paperId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.paperProvenance(paperId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("provenance");
    }
}

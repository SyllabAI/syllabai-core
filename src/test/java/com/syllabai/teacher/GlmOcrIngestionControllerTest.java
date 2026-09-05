package com.syllabai.teacher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.GlmOcrDraftMapper.ReviewFinding;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The controlled teacher surface: the five-document parser bundle (raw JSON
 * subtrees) converts cleanly into the exact service contract; the structured
 * result and review findings are exposed without leaking entities.
 */
class GlmOcrIngestionControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURES =
            Path.of("src/test/resources/fixtures/glm-ocr/october-2025-wph11-01");

    private final GlmOcrIngestionService bridge = mock(GlmOcrIngestionService.class);
    private final GlmOcrIngestionController controller = new GlmOcrIngestionController(bridge);

    @Test
    @DisplayName("a verbatim five-document bundle converts to the exact service request")
    void bundleConversion() throws Exception {
        // the bundle as a teacher would POST it: the five parser outputs as subtrees
        ObjectNode bundle = JSON.createObjectNode();
        bundle.set("qpCanonical", JSON.readTree(Files.readString(FIXTURES.resolve("qp-canonical.json"))));
        bundle.set("msCanonical", JSON.readTree(Files.readString(FIXTURES.resolve("ms-canonical.json"))));
        bundle.set("qpDraft", JSON.readTree(Files.readString(FIXTURES.resolve("qp-draft.json"))));
        bundle.set("msDraft", JSON.readTree(Files.readString(FIXTURES.resolve("ms-draft.json"))));
        bundle.set("reconciliation", JSON.readTree(Files.readString(FIXTURES.resolve("reconciliation.json"))));

        UUID operator = UUID.randomUUID();
        when(bridge.ingestPair(any(), any())).thenReturn(new GlmOcrIngestionService.PairResult(
                new GlmOcrIngestionService.DocumentStatus(false, "qp-doc", UUID.randomUUID(), 12),
                new GlmOcrIngestionService.DocumentStatus(false, "ms-doc", UUID.randomUUID(), 14),
                new GlmOcrIngestionService.PaperStatus(false, UUID.randomUUID(), "IAL WPH11/01"),
                20, 26, 20, 53, 12, 14,
                new GlmOcrIngestionService.ReconciliationStatus("OK", 0, false, 80, null),
                List.of(new ReviewFinding("QP_WARNING", "warning", "18", null, null,
                        "Q18: part marks sum (2) conflicts with printed total (8)")),
                true));

        GlmOcrIngestionController.PairResultView view = controller.ingest(operator,
                new GlmOcrIngestionController.PairRequestView(
                        bundle.get("qpCanonical"), bundle.get("msCanonical"),
                        bundle.get("qpDraft"), bundle.get("msDraft"),
                        bundle.get("reconciliation")));

        ArgumentCaptor<GlmOcrIngestionService.GlmOcrPairRequest> captor =
                ArgumentCaptor.forClass(GlmOcrIngestionService.GlmOcrPairRequest.class);
        verify(bridge).ingestPair(captor.capture(), eq(operator));
        GlmOcrIngestionService.GlmOcrPairRequest request = captor.getValue();
        assertThat(request.qpCanonical().documentId())
                .isEqualTo("e8191629-9288-55d0-833a-19be26f274bd");
        assertThat(request.msCanonical().source().checksum())
                .isEqualTo("03e12f0e44bb1f7e37d7df340fa4eb15c869ff4bff07243c3f7b942fd854408f");
        assertThat(request.qpDraft().questions()).hasSize(20);
        assertThat(request.reconciliation().qpPaperTotal()).isEqualTo(80);

        assertThat(view.qpDocument().status()).isEqualTo("INGESTED");
        assertThat(view.questions()).isEqualTo(20);
        assertThat(view.markPoints()).isEqualTo(53);
        assertThat(view.embeddingSkipped()).isTrue();
        assertThat(view.reviewFindings()).hasSize(1);
        assertThat(view.reviewFindings().get(0).detail()).contains("Q18");
    }

    @Test
    @DisplayName("the review-findings endpoint serves persisted findings for a paper")
    void findingsEndpoint() {
        UUID paperId = UUID.randomUUID();
        when(bridge.reviewFindingsForPaper(paperId)).thenReturn(Optional.of(List.of(
                new ReviewFinding("RECONCILIATION", "paper-total-conflict", null, 80, 120,
                        "QP paper total 80 vs MS paper total 120 — both preserved, never merged"))));

        List<GlmOcrIngestionController.FindingView> findings = controller.findings(paperId);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).severity()).isEqualTo("paper-total-conflict");
        assertThat(findings.get(0).qpMarks()).isEqualTo(80);
        assertThat(findings.get(0).msMarks()).isEqualTo(120);
    }

    @Test
    @DisplayName("a clean paper (existing record, empty findings) returns 200 [] — not 404")
    void findingsEmptyForCleanPaper() {
        // an existing bridge record whose review_findings JSONB is an empty array:
        // zero findings is a legitimate clean state, NOT a missing record
        UUID paperId = UUID.randomUUID();
        when(bridge.reviewFindingsForPaper(paperId)).thenReturn(Optional.of(List.of()));

        List<GlmOcrIngestionController.FindingView> findings = controller.findings(paperId);
        assertThat(findings).isNotNull();
        assertThat(findings).isEmpty(); // 200 with [] — no NotFoundException raised
    }

    @Test
    @DisplayName("unknown papers (no bridge record) fail with a clean 404, not an empty list")
    void findingsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(bridge.reviewFindingsForPaper(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.findings(unknown))
                .isInstanceOf(NotFoundException.class);
    }
}

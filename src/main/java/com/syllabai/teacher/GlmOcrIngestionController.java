package com.syllabai.teacher;

import com.syllabai.content.CanonicalDocumentDto;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.GlmOcrDraftMapper;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService;
import com.syllabai.teacher.ingestion.GlmOcrIngestionService.PairResult;
import com.syllabai.teacher.ingestion.GlmOcrMarkSchemeDraftDto;
import com.syllabai.teacher.ingestion.GlmOcrPaperDraftDto;
import com.syllabai.teacher.ingestion.GlmOcrReconciliationDto;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controlled content-ops surface for the T-C02 GLM-OCR bridge (T-C02 contract:
 * ONE entry point for one fixture pair, teacher/admin only —
 * {@code /api/v1/teacher/**} requires TEACHER/ADMIN in SecurityConfig). This is
 * NOT a learner endpoint and never exposes JPA entities: the request mirrors the
 * parser's production contract verbatim, the response is the structured bridge
 * result.
 */
@RestController
@RequestMapping("/api/v1/teacher/content/glm-ocr")
public class GlmOcrIngestionController {

    // WEB-layer binding runs on Jackson 3 (Spring Framework 7): the request DTO's
    // JsonNode fields MUST be tools.jackson types or every POST dies with
    // HttpMessageConversionException before reaching the service. (Jackson 2
    // com.fasterxml classes remain fine for IN-PROCESS mappers only.)
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GlmOcrIngestionService bridge;

    public GlmOcrIngestionController(GlmOcrIngestionService bridge) {
        this.bridge = bridge;
    }

    /**
     * Ingests one verified QP/MS pair (canonical QP + canonical MS + QP draft +
     * MS draft + reconciliation — the exact parser outputs). Safe to re-run.
     * Embedding is NOT part of this operation; it stays explicit through the
     * existing teacher content embedding endpoint.
     */
    @PostMapping(value = "/pairs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PairResultView ingest(@CurrentUserId UUID ingestedBy,
                                 @RequestBody PairRequestView request) throws Exception {
        PairResult result = bridge.ingestPair(request.toServiceRequest(), ingestedBy);
        return PairResultView.from(result);
    }

    /**
     * The persisted review findings (reconciliation + warnings) for one paper.
     * A MISSING bridge record is 404; an existing record with an empty
     * findings list is a clean 200 with {@code []} (record existence and
     * finding count are different questions — a clean pair has zero findings
     * and is still reviewable).
     */
    @GetMapping(value = "/papers/{paperId}/findings", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FindingView> findings(@PathVariable UUID paperId) {
        return bridge.reviewFindingsForPaper(paperId)
                .map(list -> list.stream().map(FindingView::from).toList())
                .orElseThrow(() -> new NotFoundException("glm-ocr bridge record for paper", paperId));
    }

    /**
     * Verbatim parser contract as received. The canonical subtrees are kept as
     * raw JSON (content-preserving; the source checksum pins the original file
     * per §8, same policy as the T-013 content endpoint).
     */
    public record PairRequestView(
            JsonNode qpCanonical,
            JsonNode msCanonical,
            JsonNode qpDraft,
            JsonNode msDraft,
            JsonNode reconciliation) {

        public GlmOcrIngestionService.GlmOcrPairRequest toServiceRequest() throws Exception {
            return new GlmOcrIngestionService.GlmOcrPairRequest(
                    JSON.treeToValue(qpCanonical, CanonicalDocumentDto.class),
                    JSON.writeValueAsString(qpCanonical),
                    JSON.treeToValue(msCanonical, CanonicalDocumentDto.class),
                    JSON.writeValueAsString(msCanonical),
                    JSON.treeToValue(qpDraft, GlmOcrPaperDraftDto.class),
                    JSON.treeToValue(msDraft, GlmOcrMarkSchemeDraftDto.class),
                    JSON.treeToValue(reconciliation, GlmOcrReconciliationDto.class));
        }
    }

    public record DocumentStatusView(String status, String documentId, int chunks) {
        static DocumentStatusView from(GlmOcrIngestionService.DocumentStatus s) {
            return new DocumentStatusView(s.duplicate() ? "DUPLICATE" : "INGESTED",
                    s.documentId(), s.chunks());
        }
    }

    public record PaperStatusView(String status, UUID paperId, String title) {
        static PaperStatusView from(GlmOcrIngestionService.PaperStatus s) {
            return new PaperStatusView(s.duplicate() ? "DUPLICATE" : "INGESTED",
                    s.paperId(), s.title());
        }
    }

    public record ReconciliationView(String status, int mismatchCount, boolean paperTotalConflict,
                                     Integer qpPaperTotal, Integer msPaperTotal) {
        static ReconciliationView from(GlmOcrIngestionService.ReconciliationStatus s) {
            return new ReconciliationView(s.status(), s.mismatchCount(),
                    s.paperTotalConflict(), s.qpPaperTotal(), s.msPaperTotal());
        }
    }

    public record FindingView(String source, String severity, String questionNumber,
                              Integer qpMarks, Integer msMarks, String detail) {
        static FindingView from(GlmOcrDraftMapper.ReviewFinding f) {
            return new FindingView(f.source(), f.severity(), f.questionNumber(),
                    f.qpMarks(), f.msMarks(), f.detail());
        }
    }

    public record PairResultView(
            DocumentStatusView qpDocument,
            DocumentStatusView msDocument,
            PaperStatusView examPaper,
            int questions,
            int parts,
            int markSchemes,
            int markPoints,
            int qpChunks,
            int msChunks,
            ReconciliationView reconciliation,
            List<FindingView> reviewFindings,
            boolean embeddingSkipped) {

        static PairResultView from(PairResult r) {
            return new PairResultView(
                    DocumentStatusView.from(r.qpDocument()),
                    DocumentStatusView.from(r.msDocument()),
                    PaperStatusView.from(r.examPaper()),
                    r.questions(), r.parts(), r.markSchemes(), r.markPoints(),
                    r.qpChunks(), r.msChunks(),
                    ReconciliationView.from(r.reconciliation()),
                    r.reviewFindings().stream().map(f -> new FindingView(
                            f.source(), f.severity(), f.questionNumber(),
                            f.qpMarks(), f.msMarks(), f.detail())).toList(),
                    r.embeddingSkipped());
        }
    }
}

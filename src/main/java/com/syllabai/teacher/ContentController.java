package com.syllabai.teacher;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.content.Document;
import com.syllabai.content.DocumentRepository;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.shared.NotFoundException;
import com.syllabai.teacher.ingestion.PastPaperDraftDto;
import com.syllabai.teacher.ingestion.PastPaperIngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Teacher content endpoints: past-paper ingestion (T-011 bridge) and the §7
 * validation workflow. Route security: /api/v1/teacher/** requires TEACHER/ADMIN.
 */
@RestController
@RequestMapping("/api/v1/teacher/content")
public class ContentController {

    private final PastPaperIngestionService ingestion;
    private final ContentReviewService review;
    private final ExamPaperRepository examPapers;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final DocumentRepository documents;

    public ContentController(PastPaperIngestionService ingestion,
                             ContentReviewService review,
                             ExamPaperRepository examPapers,
                             QuestionVersionRepository questionVersions,
                             MarkSchemeRepository markSchemes,
                             DocumentRepository documents) {
        this.ingestion = ingestion;
        this.review = review;
        this.examPapers = examPapers;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.documents = documents;
    }

    /** ingest a syllabai-parser past-paper-draft.json (schema 1.0) — all SUGGESTED */
    @PostMapping("/past-papers")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionResultView ingest(@CurrentUserId UUID ingestedBy,
                                      @Valid @RequestBody PastPaperDraftDto draft) {
        if (draft.schemaVersion() != null
                && !PastPaperDraftDto.SUPPORTED_SCHEMA.equals(draft.schemaVersion())) {
            throw new com.syllabai.shared.ConflictException(
                    "unsupported draft schemaVersion " + draft.schemaVersion()
                    + " (expected " + PastPaperDraftDto.SUPPORTED_SCHEMA + ")");
        }
        PastPaperIngestionService.IngestionSummary summary = ingestion.ingest(draft, ingestedBy);
        return new IngestionResultView(summary.paperId(), summary.questions(),
                summary.parts(), summary.markPoints(), "SUGGESTED");
    }

    /** the review queue: everything awaiting teacher validation */
    @GetMapping("/review-queue")
    public ReviewQueueView reviewQueue() {
        List<ExamPaper> papers = examPapers.findSuggested();
        List<QuestionVersion> versions = questionVersions.findSuggested();
        List<MarkScheme> schemes = markSchemes.findSuggested();
        return new ReviewQueueView(
                papers.stream().map(PaperSummary::from).toList(),
                versions.size(),
                schemes.size());
    }

    /**
     * V20 quality-enriched review queue (strongest candidates first): the same
     * SUGGESTED papers plus per-paper progress, reconciliation status, parser
     * findings and mean extraction confidence. Ordering is a triage aid — it
     * never promotes anything.
     */
    @GetMapping("/review-queue-v2")
    public ContentReviewService.EnrichedReviewQueueView reviewQueueV2() {
        return review.enrichedReviewQueue();
    }

    /**
     * Sprint 2 §7 queue intelligence: the v2 enrichment PLUS mark-scheme
     * linkage, curriculum mapping coverage and novel-coverage signals, with
     * each paper's human-legible rank reasons. Deterministic ordering — a
     * triage aid that never promotes anything or weakens any gate. Read-only.
     */
    @GetMapping("/review-queue-v3")
    public ContentReviewService.EnrichedReviewQueueViewV3 reviewQueueV3() {
        return review.enrichedReviewQueueV3();
    }

    /**
     * Full review view of one paper: every question version with its content,
     * answer key and mark-scheme state — a reviewer must see WHAT they validate
     * (§7). Read-only; the serving boundary is untouched.
     */
    @GetMapping("/exam-papers/{id}/review")
    public ContentReviewService.PaperReviewView paperReview(@PathVariable UUID id) {
        return review.paperReview(id);
    }

    /** V22: durable audit history for the paper and everything under it */
    @GetMapping("/exam-papers/{id}/audit")
    public List<ContentReviewService.AuditRowView> paperAudit(@PathVariable UUID id) {
        return review.paperAudit(id);
    }

    /**
     * Provenance view of one imported paper: the source identities that pin the
     * original QP/MS files — document ids, file names, source URIs and the
     * checksums recorded by the content store at ingestion time. Read-only
     * evidence for the content-package compiler and any downstream audit; never
     * a serving or validation authority. Fail-closed: a paper whose QP or MS
     * document row is missing has no provenance to expose.
     */
    @GetMapping("/exam-papers/{id}/provenance")
    public PaperProvenanceView paperProvenance(@PathVariable UUID id) {
        ExamPaper paper = examPapers.findById(id)
                .orElseThrow(() -> new NotFoundException("exam paper", id));
        String qpDocId = paper.questionPaperDocumentId();
        String msDocId = paper.markSchemeDocumentId();
        if (qpDocId == null || qpDocId.isBlank() || msDocId == null || msDocId.isBlank()) {
            throw new NotFoundException("provenance for exam paper", id);
        }
        // the content store keeps one row per doc_version; the imported source
        // identity is its latest version
        Document qp = documents.findTopByDocumentIdOrderByDocVersionDesc(qpDocId)
                .orElseThrow(() -> new NotFoundException("provenance for exam paper", id));
        Document ms = documents.findTopByDocumentIdOrderByDocVersionDesc(msDocId)
                .orElseThrow(() -> new NotFoundException("provenance for exam paper", id));
        return new PaperProvenanceView(paper.id(),
                new DocumentIdentity(qp.documentId(), qp.fileName(), qp.sourceUri(),
                        qp.checksum(), qp.checksumAlgorithm()),
                new DocumentIdentity(ms.documentId(), ms.fileName(), ms.sourceUri(),
                        ms.checksum(), ms.checksumAlgorithm()));
    }

    /** source identity of one ingested document (never mutated post-ingestion) */
    public record DocumentIdentity(String documentId, String fileName, String sourceUri,
                                   String checksum, String checksumAlgorithm) {
    }

    /** provenance identity of one ingested QP/MS pair (never mutated post-ingestion) */
    public record PaperProvenanceView(UUID paperId, DocumentIdentity questionPaper,
                                      DocumentIdentity markScheme) {
    }

    /**
     * V20 batch action: validate every SUGGESTED version + scheme of the paper
     * and then the paper itself, in one transaction. Fail-closed against
     * REVIEW_REQUIRED imports and REJECTED/FLAGGED versions unless forced.
     */
    @PostMapping("/exam-papers/{id}/validate-all")
    public ContentReviewService.BatchResult validateAll(@PathVariable UUID id,
                                                        @RequestParam(required = false)
                                                        Boolean force) {
        return review.validateAllForPaper(id, Boolean.TRUE.equals(force));
    }

    @PostMapping("/exam-papers/{id}/validate")
    public PaperSummary validatePaper(@PathVariable UUID id) {
        return PaperSummary.from(review.validatePaper(id));
    }

    /**
     * §7 placement: ingested papers wait in the neutral placeholder subject;
     * the reviewer places them into the real curriculum subject. Factual
     * association only — validation states and the serving boundary untouched.
     */
    @PostMapping("/exam-papers/{id}/place")
    public PaperSummary placePaper(@PathVariable UUID id,
                                   @Valid @RequestBody PlaceRequest request) {
        return PaperSummary.from(review.placePaper(id, request.subjectId()));
    }

    @PostMapping("/exam-papers/{id}/reject")
    public PaperSummary rejectPaper(@PathVariable UUID id) {
        return PaperSummary.from(review.rejectPaper(id));
    }

    @PostMapping("/question-versions/{id}/validate")
    public VersionSummary validateVersion(@PathVariable UUID id) {
        return VersionSummary.from(review.validateQuestionVersion(id));
    }

    @PostMapping("/question-versions/{id}/reject")
    public VersionSummary rejectVersion(@PathVariable UUID id) {
        return VersionSummary.from(review.rejectQuestionVersion(id));
    }

    /** V20: flag a question version (from SUGGESTED/VALIDATED — stops serving immediately) */
    @PostMapping("/question-versions/{id}/flag")
    public VersionSummary flagVersion(@PathVariable UUID id) {
        return VersionSummary.from(review.flagQuestionVersion(id));
    }

    /** V20: unflag a question version (back to SUGGESTED — re-validation required) */
    @PostMapping("/question-versions/{id}/unflag")
    public VersionSummary unflagVersion(@PathVariable UUID id) {
        return VersionSummary.from(review.unflagQuestionVersion(id));
    }

    /** V20: flag the paper itself — blocks serving of everything under it */
    @PostMapping("/exam-papers/{id}/flag")
    public PaperSummary flagPaper(@PathVariable UUID id) {
        return PaperSummary.from(review.flagPaper(id));
    }

    /** V20: unflag the paper (back to SUGGESTED) */
    @PostMapping("/exam-papers/{id}/unflag")
    public PaperSummary unflagPaper(@PathVariable UUID id) {
        return PaperSummary.from(review.unflagPaper(id));
    }

    // ── §10 topic mapping: ingestion anchors -> real curriculum topics ──

    /**
     * Map a question to its real curriculum topic(s). Ingestion parks questions
     * on disconnected per-paper anchor nodes; until a reviewer maps them here,
     * subject-scoped practice cannot see them. Factual association only —
     * validation states and the serving boundary are untouched.
     */
    @PostMapping("/questions/{questionId}/topics")
    public ContentReviewService.TopicMappingResult mapQuestionTopics(
            @PathVariable UUID questionId,
            @jakarta.validation.Valid @RequestBody TopicMappingRequest request) {
        return review.mapQuestionTopics(questionId, request.primaryNodeId(),
                request.secondaryNodeIds());
    }

    /** the question's current topic rows (shows the ingestion-anchor placeholder state) */
    @GetMapping("/questions/{questionId}/topics")
    public List<ContentReviewService.TopicRowView> questionTopicRows(
            @PathVariable UUID questionId) {
        return review.questionTopicRows(questionId);
    }

    public record TopicMappingRequest(
            @jakarta.validation.constraints.NotNull UUID primaryNodeId,
            List<UUID> secondaryNodeIds) {
    }

    @PostMapping("/mark-schemes/{id}/validate")
    public SchemeSummary validateScheme(@PathVariable UUID id,
                                        @jakarta.validation.Valid
                                        @RequestBody(required = false) SchemeValidateRequest request) {
        List<ContentReviewService.PointCriteria> criteria =
                (request == null || request.criteria() == null) ? null
                : request.criteria().stream()
                        .map(c -> new ContentReviewService.PointCriteria(
                                c.markPointId(), c.acceptanceCriteria()))
                        .toList();
        return SchemeSummary.from(review.validateMarkScheme(id, criteria));
    }

    @PostMapping("/mark-schemes/{id}/reject")
    public SchemeSummary rejectScheme(@PathVariable UUID id) {
        return SchemeSummary.from(review.rejectMarkScheme(id));
    }

    /** V20: flag a mark scheme (from SUGGESTED/VALIDATED) */
    @PostMapping("/mark-schemes/{id}/flag")
    public SchemeSummary flagScheme(@PathVariable UUID id) {
        return SchemeSummary.from(review.flagMarkScheme(id));
    }

    /** V20: unflag a mark scheme (back to SUGGESTED) */
    @PostMapping("/mark-schemes/{id}/unflag")
    public SchemeSummary unflagScheme(@PathVariable UUID id) {
        return SchemeSummary.from(review.unflagMarkScheme(id));
    }

    // ── views ───────────────────────────────────────────────────────────────

    public record IngestionResultView(UUID paperId, int questions, int parts,
                                      int markPoints, String validationState) {
    }

    public record ReviewQueueView(List<PaperSummary> papers, int suggestedVersions,
                                  int suggestedSchemes) {
    }

    public record PaperSummary(UUID id, UUID subjectId, String title, String paperCode,
                               String sessionLabel, String board, String qualification,
                               String validationState) {

        public static PaperSummary from(ExamPaper p) {
            return new PaperSummary(p.id(), p.subjectId(), p.title(), p.paperCode(),
                    p.sessionLabel(), p.board(), p.qualification(),
                    p.validationState().name());
        }
    }

    public record VersionSummary(UUID id, UUID questionId, int version, String validationState) {

        public static VersionSummary from(QuestionVersion v) {
            return new VersionSummary(v.id(), v.questionId(), v.version(),
                    v.validationState().name());
        }
    }

    public record SchemeSummary(UUID id, UUID questionVersionId, int pointCount,
                                String validationState) {

        public static SchemeSummary from(MarkScheme s) {
            return new SchemeSummary(s.id(), s.questionVersionId(), s.points().size(),
                    s.validationState().name());
        }
    }

    /**
     * @param criteria acceptance-criteria authoring, applied atomically with validation;
     *                 null/absent = validate the scheme as-is
     */
    public record SchemeValidateRequest(List<PointCriteriaUpdate> criteria) {
    }

    public record PointCriteriaUpdate(@NotNull UUID markPointId,
                                       @NotNull List<String> acceptanceCriteria) {
    }

    /** §7 placement request: the target curriculum subject. */
    public record PlaceRequest(@NotNull UUID subjectId) {
    }
}

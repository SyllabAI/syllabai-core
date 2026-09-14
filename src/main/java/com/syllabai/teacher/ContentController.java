package com.syllabai.teacher;

import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
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

    public ContentController(PastPaperIngestionService ingestion,
                             ContentReviewService review,
                             ExamPaperRepository examPapers,
                             QuestionVersionRepository questionVersions,
                             MarkSchemeRepository markSchemes) {
        this.ingestion = ingestion;
        this.review = review;
        this.examPapers = examPapers;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
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
     * Full review view of one paper: every question version with its content,
     * answer key and mark-scheme state — a reviewer must see WHAT they validate
     * (§7). Read-only; the serving boundary is untouched.
     */
    @GetMapping("/exam-papers/{id}/review")
    public ContentReviewService.PaperReviewView paperReview(@PathVariable UUID id) {
        return review.paperReview(id);
    }

    @PostMapping("/exam-papers/{id}/validate")
    public PaperSummary validatePaper(@PathVariable UUID id) {
        return PaperSummary.from(review.validatePaper(id));
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

    // ── views ───────────────────────────────────────────────────────────────

    public record IngestionResultView(UUID paperId, int questions, int parts,
                                      int markPoints, String validationState) {
    }

    public record ReviewQueueView(List<PaperSummary> papers, int suggestedVersions,
                                  int suggestedSchemes) {
    }

    public record PaperSummary(UUID id, String title, String paperCode, String sessionLabel,
                               String board, String qualification, String validationState) {

        public static PaperSummary from(ExamPaper p) {
            return new PaperSummary(p.id(), p.title(), p.paperCode(), p.sessionLabel(),
                    p.board(), p.qualification(), p.validationState().name());
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
}

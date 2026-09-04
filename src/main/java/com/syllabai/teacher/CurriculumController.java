package com.syllabai.teacher;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.knowledge.KnowledgeNode;
import com.syllabai.shared.ConflictException;
import com.syllabai.teacher.ingestion.CurriculumDraftDto;
import com.syllabai.teacher.ingestion.CurriculumIngestionService;
import jakarta.validation.Valid;
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
 * Teacher curriculum endpoints (T-010): spec-draft ingestion + the §7 node
 * validation workflow. Route security: /api/v1/teacher/** requires
 * TEACHER/ADMIN.
 */
@RestController
@RequestMapping("/api/v1/teacher/curriculum")
public class CurriculumController {

    private final CurriculumIngestionService ingestion;
    private final CurriculumReviewService review;

    public CurriculumController(CurriculumIngestionService ingestion,
                                CurriculumReviewService review) {
        this.ingestion = ingestion;
        this.review = review;
    }

    /** ingest a syllabai-parser curriculum-draft.json (schema 1.1) — all SUGGESTED */
    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionResultView ingest(@CurrentUserId UUID ingestedBy,
                                      @Valid @RequestBody CurriculumDraftDto draft) {
        if (draft.schemaVersion() != null
                && !CurriculumDraftDto.SUPPORTED_SCHEMA.equals(draft.schemaVersion())) {
            throw new ConflictException("unsupported draft schemaVersion " + draft.schemaVersion()
                    + " (expected " + CurriculumDraftDto.SUPPORTED_SCHEMA + ")");
        }
        CurriculumIngestionService.IngestionSummary summary = ingestion.ingest(draft, ingestedBy);
        return new IngestionResultView(summary.curriculumVersionId(), summary.subjectId(),
                summary.subjectRootNodeId(), summary.units(), summary.topics(),
                summary.subtopics(), "SUGGESTED");
    }

    /** curriculum review overview: node counts by validation state per version */
    @GetMapping("/versions")
    public List<CurriculumReviewService.CurriculumOverview> versions() {
        return review.versions();
    }

    /** the review queue for one version; optional status filter (SUGGESTED default queue) */
    @GetMapping("/versions/{id}/nodes")
    public List<CurriculumReviewService.NodeView> nodes(
            @PathVariable UUID id,
            @RequestParam(required = false) KnowledgeNode.ValidationStatus status) {
        return review.nodes(id, status);
    }

    @PostMapping("/nodes/{id}/validate")
    public CurriculumReviewService.NodeView validateNode(@PathVariable UUID id) {
        return review.validateNode(id);
    }

    @PostMapping("/nodes/{id}/reject")
    public CurriculumReviewService.NodeView rejectNode(@PathVariable UUID id) {
        return review.rejectNode(id);
    }

    /** the version gate: ACTIVE only when the whole subject tree is VALIDATED */
    @PostMapping("/versions/{id}/validate")
    public CurriculumReviewService.CurriculumOverview validateVersion(@PathVariable UUID id) {
        return review.validateVersion(id);
    }

    @PostMapping("/versions/{id}/archive")
    public CurriculumReviewService.CurriculumOverview archiveVersion(@PathVariable UUID id) {
        return review.archiveVersion(id);
    }

    /**
     * @param curriculumVersionId resolved-or-created curriculum version
     * @param subjectId           subject row
     * @param subjectRootNodeId   KG subject root the tree hangs under
     * @param units               units created/reused
     * @param topics              topics created/reused
     * @param subtopics           subtopics created/reused
     * @param validationState     always SUGGESTED on ingestion
     */
    public record IngestionResultView(UUID curriculumVersionId, UUID subjectId,
                                      UUID subjectRootNodeId, int units, int topics,
                                      int subtopics, String validationState) {
    }
}

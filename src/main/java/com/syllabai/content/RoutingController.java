package com.syllabai.content;

import com.syllabai.content.FetchService.FetchResult;
import com.syllabai.content.EnumerateService.EnumerateResult;
import com.syllabai.curriculum.CurriculumScopeResolver;
import com.syllabai.identity.CurrentUserId;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deterministic routing endpoints (R4, plan §7): Fetch and Enumerate — the
 * metadata-intent surfaces. Both are zero-vector on the happy path (parse →
 * bank SQL), both resolve the caller's curriculum scope first (T-C07 — an
 * unresolved active curriculum is an honest empty result, never an unscoped
 * query), and both are teacher/ops surfaces under the standard
 * {@code /api/v1/teacher/**} security posture.
 */
@RestController
@RequestMapping("/api/v1/teacher/content")
public class RoutingController {

    private final FetchService fetch;
    private final EnumerateService enumerate;
    private final CurriculumScopeResolver curriculumScopes;

    public RoutingController(FetchService fetch,
                             EnumerateService enumerate,
                             CurriculumScopeResolver curriculumScopes) {
        this.fetch = fetch;
        this.enumerate = enumerate;
        this.curriculumScopes = curriculumScopes;
    }

    /**
     * Deterministic Fetch: "mark scheme for Jan 2020 2C Q7b" → the resolved
     * paper(s), question, parts and mark-scheme points by ID. Zero vector
     * calls; a query with no recognizable metadata vocabulary comes back with
     * {@code parseDefect=true} and empty papers (the caller may fall back to
     * evidence-layer vector search per plan §7).
     */
    @GetMapping("/fetch")
    public FetchView fetch(@CurrentUserId UUID requesterId,
                           @RequestParam @NotBlank String query) {
        return curriculumScopes.resolveActive(requesterId)
                .map(scope -> FetchView.from(fetch.fetch(query, scope)))
                .orElse(FetchView.empty());
    }

    /**
     * Deterministic Enumerate: topic / spec-point / paper-axis question
     * listings straight from the bank SQL (gold-set semantics:
     * PAST_PAPER + active; REJECTED papers invisible; plan §7 dedup key).
     */
    @GetMapping("/enumerate")
    public EnumerateView enumerate(@CurrentUserId UUID requesterId,
                                   @RequestParam @NotBlank String query) {
        return curriculumScopes.resolveActive(requesterId)
                .map(scope -> EnumerateView.from(enumerate.enumerate(query, scope)))
                .orElse(EnumerateView.empty());
    }

    /**
     * Structured Enumerate for programmatic callers — node code or title,
     * year window, marks and question-type filters, no NL parse.
     */
    @GetMapping("/enumerate/structured")
    public EnumerateView enumerateStructured(@CurrentUserId UUID requesterId,
                                             @RequestParam String nodeCode,
                                             @RequestParam(required = false) String nodeTitle,
                                             @RequestParam(required = false) Integer yearFrom,
                                             @RequestParam(required = false) Integer yearTo,
                                             @RequestParam(required = false) Integer marks,
                                             @RequestParam(required = false) String questionType,
                                             @RequestParam(defaultValue = "topic") String axis) {
        return curriculumScopes.resolveActive(requesterId)
                .map(scope -> EnumerateView.from(enumerate.enumerateStructured(
                        nodeCode, nodeTitle, yearFrom, yearTo, marks, questionType,
                        "spec".equalsIgnoreCase(axis), scope)))
                .orElse(EnumerateView.empty());
    }

    // ── views ───────────────────────────────────────────────────────────────

    public record FetchQuestionView(UUID questionId, String externalRef, String stem,
                                    Integer marks, String questionType, String commandWord,
                                    List<FetchService.PartView> parts,
                                    List<FetchService.MarkPointView> markPoints) {

        static FetchQuestionView from(FetchService.FetchQuestion q) {
            return new FetchQuestionView(q.questionId(), q.externalRef(), q.stem(), q.marks(),
                    q.questionType(), q.commandWord(), q.parts(), q.markPoints());
        }
    }

    public record FetchPaperView(UUID paperId, String paperCode, String sessionLabel,
                                 String series, Integer year, String validationState,
                                 String qpDocumentId, String msDocumentId,
                                 FetchQuestionView question) {

        static FetchPaperView from(FetchService.FetchPaperHit hit) {
            return new FetchPaperView(hit.paperId(), hit.paperCode(), hit.sessionLabel(),
                    hit.series(), hit.year(), hit.validationState(), hit.qpDocumentId(),
                    hit.msDocumentId(),
                    hit.question() == null ? null : FetchQuestionView.from(hit.question()));
        }
    }

    public record FetchView(FetchQueryParser.ParsedFetchQuery parsed, boolean ambiguous,
                            boolean parseDefect, List<FetchPaperView> papers) {

        static FetchView from(FetchResult result) {
            return new FetchView(result.parsed(), result.ambiguous(), result.parseDefect(),
                    result.papers().stream().map(FetchPaperView::from).toList());
        }

        static FetchView empty() {
            return new FetchView(null, false, false, List.of());
        }
    }

    public record EnumerateView(EnumerateResult result) {

        static EnumerateView from(EnumerateResult result) {
            return new EnumerateView(result);
        }

        static EnumerateView empty() {
            return new EnumerateView(new EnumerateResult("unscoped", null, null, null,
                    null, false, List.of()));
        }
    }
}

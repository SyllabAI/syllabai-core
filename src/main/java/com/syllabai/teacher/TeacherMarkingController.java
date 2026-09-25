package com.syllabai.teacher;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.ExamPaper;
import com.syllabai.assessment.ExamPaperRepository;
import com.syllabai.identity.CurrentUserId;
import com.syllabai.identity.User;
import com.syllabai.identity.UserRepository;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.smartmark.HumanMark;
import com.syllabai.smartmark.HumanMarkRepository;
import com.syllabai.smartmark.SmartMarkAgreementEvaluation;
import com.syllabai.smartmark.SmartMarkAgreementEvaluationRepository;
import com.syllabai.smartmark.SmartMarkResult;
import com.syllabai.smartmark.SmartMarkResultRepository;
import com.syllabai.smartmark.SmartMarkService;
import com.syllabai.teacher.dto.TeacherViews;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * Teacher marking endpoints (Master Spec §15, §22). Route security:
 * /api/v1/teacher/** requires TEACHER or ADMIN (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/teacher/marking")
public class TeacherMarkingController {

    private final AnswerRepository answers;
    private final SmartMarkService smartMarkService;
    private final TeacherMarkingService teacherMarkingService;
    private final TeacherMarkingQueueService markingQueueService;
    private final SmartMarkResultRepository smartMarkResults;
    private final HumanMarkRepository humanMarks;
    private final SmartMarkAgreementEvaluationRepository agreementEvaluations;
    private final UserRepository users;
    private final ExamPaperRepository examPapers;

    public TeacherMarkingController(AnswerRepository answers,
                                    SmartMarkService smartMarkService,
                                    TeacherMarkingService teacherMarkingService,
                                    TeacherMarkingQueueService markingQueueService,
                                    SmartMarkResultRepository smartMarkResults,
                                    HumanMarkRepository humanMarks,
                                    SmartMarkAgreementEvaluationRepository agreementEvaluations,
                                    UserRepository users,
                                    ExamPaperRepository examPapers) {
        this.answers = answers;
        this.smartMarkService = smartMarkService;
        this.teacherMarkingService = teacherMarkingService;
        this.markingQueueService = markingQueueService;
        this.smartMarkResults = smartMarkResults;
        this.humanMarks = humanMarks;
        this.agreementEvaluations = agreementEvaluations;
        this.users = users;
        this.examPapers = examPapers;
    }

    /** rows per marking-queue page when only `page` is given (G-5) */
    static final int DEFAULT_ANSWER_PAGE_SIZE = 50;
    /** hard bound on rows per marking-queue page (G-5) */
    static final int MAX_ANSWER_PAGE_SIZE = 200;

    /**
     * Direct-call compatibility surface: the UNPAGED marking queue (the shape
     * the web marking UI and the calibration harness read). Not a handler —
     * the paginated {@link #queue(String, Integer, Integer)} owns the route.
     */
    public List<TeacherViews.AnswerMarkingView> queue(String state) {
        return queueList(parseMarkingState(state));
    }

    private List<TeacherViews.AnswerMarkingView> queueList(Answer.MarkingState filter) {
        List<Answer> queue = answers.findByMarkingState(filter);
        // one batched identity lookup so the queue is self-contained (T-029:
        // the teacher reads whose answer it is without a client-side join)
        Map<UUID, String> names = learnerNames(
                queue.stream().map(a -> a.attempt().learnerId()).collect(Collectors.toSet()));
        Map<UUID, ExamPaper> papers = paperContext(queue);
        return queue.stream()
                .map(a -> TeacherViews.answer(a, names.get(a.attempt().learnerId()),
                        null, null, paperTitle(papers, a)))
                .toList();
    }

    /** batched paper titles for the queue rows (G-5); empty when no answer has a paper */
    private Map<UUID, ExamPaper> paperContext(List<Answer> queue) {
        Set<UUID> paperIds = queue.stream()
                .map(a -> a.attempt().question().examPaperId())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return paperIds.isEmpty()
                ? Map.of()
                : examPapers.findAllById(paperIds).stream()
                        .collect(Collectors.toMap(ExamPaper::id, p -> p));
    }

    private static String paperTitle(Map<UUID, ExamPaper> papers, Answer a) {
        ExamPaper paper = papers.get(a.attempt().question().examPaperId());
        return paper == null ? null : paper.title();
    }

    /**
     * Marking queue by state with OPT-IN pagination (G-5, the F-161 class-C
     * note). Without page/size params the response is the plain
     * AnswerMarkingView list — the unchanged contract. With page and/or size
     * present the response is an {@link TeacherViews.AnswerMarkingPageView}
     * envelope: the same read model under a TOTAL order (createdAt asc, then
     * id asc — stable page boundaries while rows move through states), sliced
     * by the database's own count, never an estimate. Rows-per-page bounds:
     * 1..200, default 50.
     */
    @GetMapping("/answers")
    public Object queue(@RequestParam(defaultValue = "PENDING") String state,
                        @RequestParam(required = false) Integer page,
                        @RequestParam(required = false) Integer size) {
        Answer.MarkingState filter = parseMarkingState(state);
        if (page == null && size == null) {
            return queueList(filter);
        }
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_ANSWER_PAGE_SIZE : size;
        if (p < 0) {
            throw new BadRequestException("page must be >= 0");
        }
        if (s < 1 || s > MAX_ANSWER_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_ANSWER_PAGE_SIZE);
        }
        Page<Answer> result = answers.findPageByMarkingState(filter,
                PageRequest.of(p, s, Sort.by(Sort.Order.asc("createdAt"),
                        Sort.Order.asc("id"))));
        Map<UUID, String> names = learnerNames(result.getContent().stream()
                .map(a -> a.attempt().learnerId()).collect(Collectors.toSet()));
        Map<UUID, ExamPaper> papers = paperContext(result.getContent());
        List<TeacherViews.AnswerMarkingView> items = result.getContent().stream()
                .map(a -> TeacherViews.answer(a, names.get(a.attempt().learnerId()),
                        null, null, paperTitle(papers, a)))
                .toList();
        return new TeacherViews.AnswerMarkingPageView(items, p, s,
                result.getTotalElements(), result.getTotalPages());
    }

    private Answer.MarkingState parseMarkingState(String state) {
        try {
            return Answer.MarkingState.valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            // C-9: an unknown filter value is a malformed request (400), not a
            // missing resource — 404 is reserved for real not-found lookups.
            throw new BadRequestException("unknown marking state: " + state
                    + " (expected PENDING, SMART_MARKED, HUMAN_MARKED or OVERRIDDEN)");
        }
    }

    /**
     * Direct-call compatibility surface: the UNPAGED v2 queue. Not a handler —
     * the paginated {@link #queueV2(String, Integer, Integer)} owns the route.
     */
    public TeacherMarkingQueueService.MarkingQueueView queueV2(String state) {
        return markingQueueService.markingQueue(parseMarkingState(state));
    }

    /**
     * The v2 marking queue with OPT-IN pagination (G-5): paper-GROUP pages —
     * one mark scheme in working memory per page, whole papers never split
     * across a boundary. Without page/size the response is the unchanged full
     * view. Papers-per-page bounds: 1..100, default 5. Validation lives in
     * the service (unit-pinned there).
     */
    @GetMapping("/queue-v2")
    public Object queueV2(@RequestParam(defaultValue = "PENDING") String state,
                          @RequestParam(required = false) Integer page,
                          @RequestParam(required = false) Integer size) {
        Answer.MarkingState filter = parseMarkingState(state);
        if (page == null && size == null) {
            return markingQueueService.markingQueue(filter);
        }
        return markingQueueService.markingQueue(filter, page, size);
    }

    /**
     * Marking throughput metrics (§6): workload by state, authoritative human
     * marks in the 24h/7d windows, pending-by-paper leaders, oldest pending
     * age. Counts of what happened — never estimates. Read-only.
     */
    @GetMapping("/throughput")
    public TeacherMarkingQueueService.ThroughputView throughput() {
        return markingQueueService.throughput();
    }

    /**
     * Bounded Smart Mark batch (§6): runs the existing per-answer pipeline once
     * per answer, each item its own transaction (partial success preserved).
     * Idempotent — already-marked answers are skipped, not re-marked. The κ
     * gate and evidence contract are untouched.
     */
    @PostMapping("/smart-mark-batch")
    public TeacherMarkingQueueService.SmartMarkBatchView smartMarkBatch(
            @Valid @RequestBody SmartMarkBatchRequest request) {
        return markingQueueService.smartMarkBatch(request.answerIds());
    }

    /** @param answerIds 1–50 answer ids to smart-mark; deduplicated, order-preserving */
    public record SmartMarkBatchRequest(
            @NotNull @NotEmpty @Size(max = TeacherMarkingQueueService.SMART_MARK_BATCH_LIMIT)
            List<UUID> answerIds) {
    }

    @GetMapping("/answers/{id}")
    public TeacherViews.AnswerMarkingView answer(@PathVariable UUID id) {
        Answer answer = answers.findWithPartAndAttempt(id)
                .orElseThrow(() -> new NotFoundException("answer", id));
        SmartMarkResult smart = smartMarkResults.findLatest(id).orElse(null);
        HumanMark human = humanMarks.findLatest(id).orElse(null);
        String learnerName = learnerNames(Set.of(answer.attempt().learnerId()))
                .get(answer.attempt().learnerId());
        return TeacherViews.answer(answer, learnerName, smart, human);
    }

    /** run the Smart Mark pipeline once against one answer (append-only history) */
    @PostMapping("/answers/{id}/smart-mark")
    public TeacherViews.SmartMarkView smartMark(@PathVariable UUID id) {
        SmartMarkResult result = smartMarkService.markAnswer(id);
        return TeacherViews.SmartMarkView.from(result);
    }

    @PostMapping("/answers/{id}/human-mark")
    @ResponseStatus(HttpStatus.CREATED)
    public TeacherViews.HumanMarkView humanMark(@CurrentUserId UUID markerId,
                                                @PathVariable UUID id,
                                                @Valid @RequestBody HumanMarkRequest request) {
        HumanMark mark = teacherMarkingService.recordHumanMark(
                id, markerId, request.marksAwarded(),
                request.perPointDecisions(), request.comments());
        return TeacherViews.HumanMarkView.from(mark);
    }

    /** recompute the κ agreement gate (scope: one paper, or all when omitted) */
    @PostMapping("/kappa/evaluate")
    @ResponseStatus(HttpStatus.CREATED)
    public KappaEvaluationView evaluateKappa(@CurrentUserId UUID computedBy,
                                             @RequestBody(required = false) KappaScopeRequest request) {
        SmartMarkAgreementEvaluation evaluation = teacherMarkingService.evaluateAgreement(
                request == null ? null : request.paperId(), computedBy);
        return KappaEvaluationView.from(evaluation);
    }

    @GetMapping("/kappa/latest")
    public KappaEvaluationView latestKappa(@RequestParam(required = false) UUID paperId) {
        SmartMarkAgreementEvaluation evaluation = paperId == null
                ? agreementEvaluations.findFirstByScopeOrderByComputedAtDesc(
                        SmartMarkAgreementEvaluation.SCOPE_ALL).orElse(null)
                : agreementEvaluations.findFirstByScopeAndExamPaperIdOrderByComputedAtDesc(
                        SmartMarkAgreementEvaluation.SCOPE_PAPER, paperId).orElse(null);
        if (evaluation == null) {
            throw new NotFoundException("kappa evaluation", paperId == null ? "ALL" : paperId);
        }
        return KappaEvaluationView.from(evaluation);
    }

    /**
     * @param marksAwarded      authoritative marks for the answer's part
     * @param perPointDecisions {markPointId: 0|1} — drives κ pairing
     * @param comments          marker rationale
     */
    public record HumanMarkRequest(
            @NotNull @Min(0) @Max(99) Integer marksAwarded,
            Map<String, Integer> perPointDecisions,
            String comments) {
    }

    public record KappaScopeRequest(UUID paperId) {
    }

    /** display names for the queue read model; unknown ids resolve to null */
    private Map<UUID, String> learnerNames(Set<UUID> learnerIds) {
        if (learnerIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(learnerIds).stream()
                .collect(Collectors.toMap(User::id, User::displayName, (a, b) -> a));
    }

    public record KappaEvaluationView(UUID id, String scope, UUID paperId, int sampleSize,
                                      double kappa, double observedAgreement, double threshold,
                                      boolean passed, java.time.Instant computedAt) {

        public static KappaEvaluationView from(SmartMarkAgreementEvaluation e) {
            return new KappaEvaluationView(e.id(), e.scope(), e.examPaperId(),
                    e.sampleSize(), e.kappa(), e.observedAgreement(), e.threshold(),
                    e.passed(), e.computedAt());
        }
    }
}

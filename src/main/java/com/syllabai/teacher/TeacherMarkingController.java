package com.syllabai.teacher;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
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

    public TeacherMarkingController(AnswerRepository answers,
                                    SmartMarkService smartMarkService,
                                    TeacherMarkingService teacherMarkingService,
                                    TeacherMarkingQueueService markingQueueService,
                                    SmartMarkResultRepository smartMarkResults,
                                    HumanMarkRepository humanMarks,
                                    SmartMarkAgreementEvaluationRepository agreementEvaluations,
                                    UserRepository users) {
        this.answers = answers;
        this.smartMarkService = smartMarkService;
        this.teacherMarkingService = teacherMarkingService;
        this.markingQueueService = markingQueueService;
        this.smartMarkResults = smartMarkResults;
        this.humanMarks = humanMarks;
        this.agreementEvaluations = agreementEvaluations;
        this.users = users;
    }

    /** marking queue by state (PENDING / SMART_MARKED / HUMAN_MARKED / OVERRIDDEN) */
    @GetMapping("/answers")
    public List<TeacherViews.AnswerMarkingView> queue(
            @RequestParam(defaultValue = "PENDING") String state) {
        Answer.MarkingState filter;
        try {
            filter = Answer.MarkingState.valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            // C-9: an unknown filter value is a malformed request (400), not a
            // missing resource — 404 is reserved for real not-found lookups.
            throw new BadRequestException("unknown marking state: " + state
                    + " (expected PENDING, SMART_MARKED, HUMAN_MARKED or OVERRIDDEN)");
        }
        List<Answer> queue = answers.findByMarkingState(filter);
        // one batched identity lookup so the queue is self-contained (T-029:
        // the teacher reads whose answer it is without a client-side join)
        Map<UUID, String> names = learnerNames(
                queue.stream().map(a -> a.attempt().learnerId()).collect(Collectors.toSet()));
        return queue.stream()
                .map(a -> TeacherViews.answer(a, names.get(a.attempt().learnerId())))
                .toList();
    }

    /**
     * Marking throughput lane (sprint 2 §6/§7): the deterministic paper-grouped
     * queue — ordered oldest-waiting-first with one mark scheme in working
     * memory at a time, each item carrying paper context, the newest Smart
     * Mark run and the mark→next link. Ordering is a workflow aid; it never
     * changes any mark, gate or evidence semantic.
     */
    @GetMapping("/queue-v2")
    public TeacherMarkingQueueService.MarkingQueueView queueV2(
            @RequestParam(defaultValue = "PENDING") String state) {
        Answer.MarkingState filter;
        try {
            filter = Answer.MarkingState.valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("unknown marking state: " + state
                    + " (expected PENDING, SMART_MARKED, HUMAN_MARKED or OVERRIDDEN)");
        }
        return markingQueueService.markingQueue(filter);
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

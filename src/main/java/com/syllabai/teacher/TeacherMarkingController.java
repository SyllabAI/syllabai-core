package com.syllabai.teacher;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.identity.CurrentUserId;
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
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
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
 * Teacher marking endpoints (Master Spec §15, §22). Route security:
 * /api/v1/teacher/** requires TEACHER or ADMIN (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/teacher/marking")
public class TeacherMarkingController {

    private final AnswerRepository answers;
    private final SmartMarkService smartMarkService;
    private final TeacherMarkingService teacherMarkingService;
    private final SmartMarkResultRepository smartMarkResults;
    private final HumanMarkRepository humanMarks;
    private final SmartMarkAgreementEvaluationRepository agreementEvaluations;

    public TeacherMarkingController(AnswerRepository answers,
                                    SmartMarkService smartMarkService,
                                    TeacherMarkingService teacherMarkingService,
                                    SmartMarkResultRepository smartMarkResults,
                                    HumanMarkRepository humanMarks,
                                    SmartMarkAgreementEvaluationRepository agreementEvaluations) {
        this.answers = answers;
        this.smartMarkService = smartMarkService;
        this.teacherMarkingService = teacherMarkingService;
        this.smartMarkResults = smartMarkResults;
        this.humanMarks = humanMarks;
        this.agreementEvaluations = agreementEvaluations;
    }

    /** marking queue by state (PENDING / SMART_MARKED / HUMAN_MARKED / OVERRIDDEN) */
    @GetMapping("/answers")
    public List<TeacherViews.AnswerMarkingView> queue(
            @RequestParam(defaultValue = "PENDING") String state) {
        Answer.MarkingState filter;
        try {
            filter = Answer.MarkingState.valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("marking state", state);
        }
        return answers.findByMarkingState(filter).stream()
                .map(TeacherViews::answer)
                .toList();
    }

    @GetMapping("/answers/{id}")
    public TeacherViews.AnswerMarkingView answer(@PathVariable UUID id) {
        Answer answer = answers.findWithPartAndAttempt(id)
                .orElseThrow(() -> new NotFoundException("answer", id));
        SmartMarkResult smart = smartMarkResults.findLatest(id).orElse(null);
        HumanMark human = humanMarks.findLatest(id).orElse(null);
        return TeacherViews.answer(answer, smart, human);
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

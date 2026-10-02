package com.syllabai.smartmark;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.AttemptRepository;
import com.syllabai.assessment.EvidencePublisher;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionTopic;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.shared.events.SmartMarkCompletedEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Smart Mark orchestration (Master Spec §15): loads the scheme scope for an answer,
 * runs the {@link SmartMarkPipeline}, persists an append-only {@link SmartMarkResult},
 * applies provisional marks, and — only when the κ ≥ 0.60 agreement gate has released
 * Smart Mark — lets the marks drive the evidence contract. Before the gate passes,
 * smart marks are provisional and a human mark remains required for evidence.
 *
 * <p>Scheme honesty (V34, gap G-2): only a VALIDATED scheme backs marking. When the
 * question version's newest scheme is SUGGESTED / REJECTED / FLAGGED, the run is
 * refused with an honest {@code SCHEME_NOT_VALIDATED} result row (stamped with the
 * scheme it refused) — nothing is marked, no state changes, no evidence fires. The
 * refusal row keeps the calibration dataset complete: it records that marking was
 * attempted and why it did not happen.</p>
 */
@Service
public class SmartMarkService {

    private static final Logger log = LoggerFactory.getLogger(SmartMarkService.class);

    private final AnswerRepository answers;
    private final AttemptRepository attempts;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final SmartMarkResultRepository smartMarkResults;
    private final SmartMarkAgreementEvaluationRepository agreementEvaluations;
    private final QuestionTopicRepository questionTopics;
    private final EvidencePublisher evidencePublisher;
    private final SmartMarkPipeline pipeline;
    private final ApplicationEventPublisher events;

    public SmartMarkService(AnswerRepository answers,
                            AttemptRepository attempts,
                            QuestionVersionRepository questionVersions,
                            MarkSchemeRepository markSchemes,
                            SmartMarkResultRepository smartMarkResults,
                            SmartMarkAgreementEvaluationRepository agreementEvaluations,
                            QuestionTopicRepository questionTopics,
                            EvidencePublisher evidencePublisher,
                            SmartMarkPipeline pipeline,
                            ApplicationEventPublisher events) {
        this.answers = answers;
        this.attempts = attempts;
        this.questionVersions = questionVersions;
        this.markSchemes = markSchemes;
        this.smartMarkResults = smartMarkResults;
        this.agreementEvaluations = agreementEvaluations;
        this.questionTopics = questionTopics;
        this.evidencePublisher = evidencePublisher;
        this.pipeline = pipeline;
        this.events = events;
    }

    /**
     * Mark one answer through the pipeline.
     *
     * @return the persisted result row (accepted or failed)
     */
    @Transactional
    public SmartMarkResult markAnswer(UUID answerId) {
        // Evidence-state concurrency fix: same serialization point as human
        // marking (TeacherMarkingService) — the attempt row lock is taken
        // BEFORE loading attempt state, so a κ-released smart mark racing a
        // human mark (or another smart mark) on the same attempt re-reads the
        // winner's committed state instead of acting on a stale evidenceEmitted
        // flag. Pre-κ (provisional) runs take the lock too: identical code
        // path, and provisional marks still write answer/attempt state.
        UUID attemptId = answers.findAttemptIdById(answerId)
                .orElseThrow(() -> new NotFoundException("answer", answerId));
        attempts.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new NotFoundException("attempt", attemptId));
        Answer answer = answers.findWithPartAndAttempt(answerId)
                .orElseThrow(() -> new NotFoundException("answer", answerId));
        Attempt attempt = answer.attempt();
        Question question = attempt.question();
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("question version", question.id()));
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdAndValidationStateOrderByCreatedAtDesc(
                        version.id(), MarkScheme.ValidationState.VALIDATED)
                .orElse(null);
        if (scheme == null) {
            return refuseUnvalidatedScheme(answer, version.id());
        }

        List<com.syllabai.assessment.MarkPoint> inScope = scheme.points().stream()
                .filter(p -> answer.questionPartId().equals(p.questionPartId()))
                .toList();
        MarkingContext context = new MarkingContext(answer, answer.questionPart(), scheme, inScope);
        SmartMarkPipeline.Decision decision = pipeline.run(context);

        SmartMarkResult result = smartMarkResults.save(new SmartMarkResult(
                answer,
                pipeline.candidateModelId(decision),
                decision.marksAwarded(),
                pipeline.candidateConfidence(decision),
                decision.accepted(),
                decision.breakdown(),
                decision.failureReason(),
                pipeline.candidateRawOutput(decision),
                scheme.id(),
                scheme.validationState().name()));

        boolean authoritative = false;
        if (decision.accepted()) {
            answer.smartMarked(decision.marksAwarded());
            attempt.smartMarked();
            recomputeAttemptTotal(attempt);
            answers.save(answer);
            authoritative = kappaGatePassed(question.examPaperId());
            if (authoritative) {
                // fire only when the attempt's marking is COMPLETE (no PENDING
                // part left): a multi-part attempt's total is partial until its
                // last part is marked, and the settled row must agree with the
                // evidence event (full marks = mastery evidence)
                boolean complete = answers
                        .findByAttemptIdOrderByQuestionPartId(attempt.id()).stream()
                        .noneMatch(a -> a.markingState() == Answer.MarkingState.PENDING);
                if (complete) {
                    List<QuestionTopic> secondary = questionTopics.findByQuestionId(question.id());
                    evidencePublisher.publishGraded(attempt, question, secondary);
                    log.info("smart mark {} authoritative (κ gate passed) — evidence fired for attempt {}",
                            result.id(), attempt.id());
                } else {
                    log.info("smart mark {} authoritative (κ gate passed) but attempt {} "
                            + "still has PENDING parts — evidence waits for the completing mark",
                            result.id(), attempt.id());
                }
            } else {
                log.info("smart mark {} provisional (κ gate not passed) — human mark still required",
                        result.id());
            }
        }

        events.publishEvent(new SmartMarkCompletedEvent(
                answer.id(), attempt.id(), attempt.learnerId(), question.id(),
                decision.marksAwarded(), MarkingValidator.pointMarkCeiling(context),
                decision.accepted(), decision.failureReason(),
                pipeline.candidateModelId(decision), SmartMarkResult.PIPELINE_VERSION,
                authoritative, Instant.now()));
        return result;
    }

    /**
     * Smart-mark EVERY part answer of one attempt in a single pass (the learner
     * "Smart mark attempt" surface, F-047 learner half): the attempt row lock is
     * taken once, the question version and VALIDATED scheme are resolved once,
     * and the pipeline's batch topology marks all parts with ONE candidate-
     * generation call (per-part fallback preserves the classic topology when
     * the batch call fails). Result rows, provisional mark application, event
     * publication and κ gating are byte-identical per part to
     * {@link #markAnswer} — only the load/generation topology is batched.
     *
     * <p>Compared with K × {@code markAnswer} on a K-part attempt: the
     * version/scheme/answers loads collapse from K rounds to one, the
     * candidate generation collapses from K reasoning-model round trips to one,
     * the κ gate is evaluated once instead of per accepted part, and the
     * attempt total is recomputed once after all parts are applied.</p>
     *
     * @return one persisted result row per part answer (accepted or failed),
     *         keyed by answer id in the attempt's part order
     * @throws NotFoundException     unknown attempt
     * @throws BadRequestException   the attempt carries no part answers
     */
    @Transactional
    public Map<UUID, SmartMarkResult> markAttempt(UUID attemptId) {
        // same serialization point as markAnswer/human marking: the attempt row
        // lock BEFORE any attempt/answer state enters the persistence context
        Attempt attempt = attempts.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new NotFoundException("attempt", attemptId));
        List<Answer> attemptAnswers =
                answers.findByAttemptIdOrderByQuestionPartId(attemptId);
        if (attemptAnswers.isEmpty()) {
            throw new BadRequestException("attempt carries no part answers");
        }
        Question question = attempt.question();
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("question version", question.id()));
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdAndValidationStateOrderByCreatedAtDesc(
                        version.id(), MarkScheme.ValidationState.VALIDATED)
                .orElse(null);
        if (scheme == null) {
            // honest refusal per part (V34, gap G-2): every part gets its own
            // SCHEME_NOT_VALIDATED row so the calibration dataset stays complete
            Map<UUID, SmartMarkResult> refusals = new LinkedHashMap<>();
            for (Answer answer : attemptAnswers) {
                refusals.put(answer.id(), refuseUnvalidatedScheme(answer, version.id()));
            }
            return refusals;
        }

        List<MarkingContext> contexts = attemptAnswers.stream()
                .map(answer -> new MarkingContext(answer, answer.questionPart(), scheme,
                        scheme.points().stream()
                                .filter(p -> answer.questionPartId().equals(p.questionPartId()))
                                .toList()))
                .toList();
        List<SmartMarkPipeline.Decision> decisions = pipeline.runBatch(contexts);

        boolean anyAccepted = decisions.stream()
                .anyMatch(SmartMarkPipeline.Decision::accepted);
        // κ gate evaluated ONCE per pass (evaluations cannot change mid-transaction,
        // so per-part recomputation in the markAnswer loop was redundant work)
        boolean authoritative = anyAccepted && kappaGatePassed(question.examPaperId());

        Map<UUID, SmartMarkResult> results = new LinkedHashMap<>();
        for (int i = 0; i < attemptAnswers.size(); i++) {
            Answer answer = attemptAnswers.get(i);
            MarkingContext context = contexts.get(i);
            SmartMarkPipeline.Decision decision = decisions.get(i);
            SmartMarkResult result = smartMarkResults.save(new SmartMarkResult(
                    answer,
                    pipeline.candidateModelId(decision),
                    decision.marksAwarded(),
                    pipeline.candidateConfidence(decision),
                    decision.accepted(),
                    decision.breakdown(),
                    decision.failureReason(),
                    pipeline.candidateRawOutput(decision),
                    scheme.id(),
                    scheme.validationState().name()));
            if (decision.accepted()) {
                answer.smartMarked(decision.marksAwarded());
                attempt.smartMarked();
                answers.save(answer);
            }
            events.publishEvent(new SmartMarkCompletedEvent(
                    answer.id(), attempt.id(), attempt.learnerId(), question.id(),
                    decision.marksAwarded(), MarkingValidator.pointMarkCeiling(context),
                    decision.accepted(), decision.failureReason(),
                    pipeline.candidateModelId(decision), SmartMarkResult.PIPELINE_VERSION,
                    authoritative && decision.accepted(), Instant.now()));
            results.put(answer.id(), result);
        }

        if (anyAccepted) {
            // one recompute after every part is applied — the serial loop's
            // per-part recomputes all observed the same final sum
            recomputeAttemptTotal(attempt);
            if (authoritative) {
                // fire only when the attempt's marking is COMPLETE (no PENDING
                // part left) — a rejected part stays PENDING and honestly withholds
                // evidence, exactly like the serial path's completing-part check
                boolean complete = answers
                        .findByAttemptIdOrderByQuestionPartId(attempt.id()).stream()
                        .noneMatch(a -> a.markingState() == Answer.MarkingState.PENDING);
                if (complete) {
                    List<QuestionTopic> secondary = questionTopics.findByQuestionId(question.id());
                    evidencePublisher.publishGraded(attempt, question, secondary);
                    log.info("smart mark batch authoritative (κ gate passed) — evidence "
                            + "fired for attempt {}", attempt.id());
                } else {
                    log.info("smart mark batch authoritative (κ gate passed) but attempt {} "
                            + "still has PENDING parts — evidence waits for the completing mark",
                            attempt.id());
                }
            }
        }
        log.info("smart mark batch pass: attempt {} ({} parts, authoritative={})",
                attemptId, attemptAnswers.size(), authoritative);
        return results;
    }

    /**
     * Honest refusal (V34, gap G-2): no VALIDATED scheme exists for the question
     * version, so nothing is marked, no state changes and no evidence fires —
     * but the attempt is recorded as an append-only result row stamped with the
     * scheme it refused (the newest scheme in whatever state it is in), so the
     * calibration dataset shows marking was attempted and why it did not happen.
     * A question version with no scheme at all keeps the original NotFound
     * behaviour (the V20 coverage guard makes that rare: VALIDATED papers always
     * carry schemes).
     */
    private SmartMarkResult refuseUnvalidatedScheme(Answer answer, UUID questionVersionId) {
        MarkScheme refused = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(questionVersionId)
                .orElseThrow(() -> new NotFoundException("mark scheme", questionVersionId));
        log.info("smart mark refused for answer {} — newest scheme {} is {} "
                        + "(only VALIDATED schemes back marking)",
                answer.id(), refused.id(), refused.validationState());
        SmartMarkResult result = smartMarkResults.save(new SmartMarkResult(
                answer, null, 0, null, false,
                List.of(), "SCHEME_NOT_VALIDATED", null,
                refused.id(), refused.validationState().name()));
        events.publishEvent(new SmartMarkCompletedEvent(
                answer.id(), answer.attempt().id(), answer.attempt().learnerId(),
                answer.attempt().question().id(), 0,
                refused.points().stream()
                        .filter(p -> answer.questionPartId().equals(p.questionPartId()))
                        .mapToInt(com.syllabai.assessment.MarkPoint::marks)
                        .sum(),
                false, "SCHEME_NOT_VALIDATED", null, SmartMarkResult.PIPELINE_VERSION,
                false, Instant.now()));
        return result;
    }

    /**
     * κ release gate: the newest evaluation for scope ALL, or the paper-scoped one,
     * must have passed. Absence of any evaluation = gated (fail-closed).
     */
    public boolean kappaGatePassed(UUID examPaperId) {
        boolean global = agreementEvaluations
                .findFirstByScopeOrderByComputedAtDesc(SmartMarkAgreementEvaluation.SCOPE_ALL)
                .map(SmartMarkAgreementEvaluation::passed)
                .orElse(false);
        boolean paper = examPaperId != null && agreementEvaluations
                .findFirstByScopeAndExamPaperIdOrderByComputedAtDesc(
                        SmartMarkAgreementEvaluation.SCOPE_PAPER, examPaperId)
                .map(SmartMarkAgreementEvaluation::passed)
                .orElse(false);
        return global || paper;
    }

    private void recomputeAttemptTotal(Attempt attempt) {
        int total = answers.findByAttemptIdOrderByQuestionPartId(attempt.id()).stream()
                .map(Answer::marksAwarded)
                .filter(m -> m != null)
                .mapToInt(Integer::intValue)
                .sum();
        attempt.recordTotalMarks(total, attempt.question().marks());
    }
}

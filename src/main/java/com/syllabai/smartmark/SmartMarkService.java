package com.syllabai.smartmark;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.EvidencePublisher;
import com.syllabai.assessment.MarkScheme;
import com.syllabai.assessment.MarkSchemeRepository;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionTopic;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.assessment.QuestionVersion;
import com.syllabai.assessment.QuestionVersionRepository;
import com.syllabai.shared.NotFoundException;
import com.syllabai.shared.events.SmartMarkCompletedEvent;
import java.time.Instant;
import java.util.List;
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
 */
@Service
public class SmartMarkService {

    private static final Logger log = LoggerFactory.getLogger(SmartMarkService.class);

    private final AnswerRepository answers;
    private final QuestionVersionRepository questionVersions;
    private final MarkSchemeRepository markSchemes;
    private final SmartMarkResultRepository smartMarkResults;
    private final SmartMarkAgreementEvaluationRepository agreementEvaluations;
    private final QuestionTopicRepository questionTopics;
    private final EvidencePublisher evidencePublisher;
    private final SmartMarkPipeline pipeline;
    private final ApplicationEventPublisher events;

    public SmartMarkService(AnswerRepository answers,
                            QuestionVersionRepository questionVersions,
                            MarkSchemeRepository markSchemes,
                            SmartMarkResultRepository smartMarkResults,
                            SmartMarkAgreementEvaluationRepository agreementEvaluations,
                            QuestionTopicRepository questionTopics,
                            EvidencePublisher evidencePublisher,
                            SmartMarkPipeline pipeline,
                            ApplicationEventPublisher events) {
        this.answers = answers;
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
        Answer answer = answers.findWithPartAndAttempt(answerId)
                .orElseThrow(() -> new NotFoundException("answer", answerId));
        Attempt attempt = answer.attempt();
        Question question = attempt.question();
        QuestionVersion version = questionVersions
                .findByQuestionIdOrderByVersionDesc(question.id()).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("question version", question.id()));
        MarkScheme scheme = markSchemes
                .findFirstByQuestionVersionIdOrderByCreatedAtDesc(version.id())
                .orElseThrow(() -> new NotFoundException("mark scheme", version.id()));

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
                pipeline.candidateRawOutput(decision)));

        boolean authoritative = false;
        if (decision.accepted()) {
            answer.smartMarked(decision.marksAwarded());
            attempt.smartMarked();
            recomputeAttemptTotal(attempt);
            answers.save(answer);
            authoritative = kappaGatePassed(question.examPaperId());
            if (authoritative) {
                List<QuestionTopic> secondary = questionTopics.findByQuestionId(question.id());
                evidencePublisher.publishGraded(attempt, question, secondary);
                log.info("smart mark {} authoritative (κ gate passed) — evidence fired for attempt {}",
                        result.id(), attempt.id());
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

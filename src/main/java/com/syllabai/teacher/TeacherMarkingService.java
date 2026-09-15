package com.syllabai.teacher;

import com.syllabai.assessment.Answer;
import com.syllabai.assessment.AnswerRepository;
import com.syllabai.assessment.Attempt;
import com.syllabai.assessment.EvidencePublisher;
import com.syllabai.assessment.Question;
import com.syllabai.assessment.QuestionTopic;
import com.syllabai.assessment.QuestionTopicRepository;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import com.syllabai.shared.events.HumanMarkRecordedEvent;
import com.syllabai.smartmark.HumanMark;
import com.syllabai.smartmark.HumanMarkRepository;
import com.syllabai.smartmark.KappaAgreementService;
import com.syllabai.smartmark.SmartMarkAgreementEvaluation;
import com.syllabai.smartmark.SmartMarkAgreementEvaluationRepository;
import com.syllabai.smartmark.SmartMarkResult;
import com.syllabai.smartmark.SmartMarkResultRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teacher marking workflow (Master Spec §15): human marks are the authoritative
 * grade. The first authoritative mark on a structured attempt fires the evidence
 * contract (BKT/BDT observe it); later marks on the same attempt are overrides —
 * they revise marks for research and the κ calibration set, and never re-run BKT.
 *
 * <p>κ agreement (F-161): paired per-mark-point decisions between the newest accepted
 * Smart Mark run and the newest human mark, Cohen's κ over the binary point
 * decisions, persisted with the threshold so the release decision is auditable.</p>
 */
@Service
public class TeacherMarkingService {

    private static final Logger log = LoggerFactory.getLogger(TeacherMarkingService.class);

    private final AnswerRepository answers;
    private final HumanMarkRepository humanMarks;
    private final SmartMarkResultRepository smartMarkResults;
    private final SmartMarkAgreementEvaluationRepository agreementEvaluations;
    private final QuestionTopicRepository questionTopics;
    private final EvidencePublisher evidencePublisher;
    private final ApplicationEventPublisher events;

    public TeacherMarkingService(AnswerRepository answers,
                                 HumanMarkRepository humanMarks,
                                 SmartMarkResultRepository smartMarkResults,
                                 SmartMarkAgreementEvaluationRepository agreementEvaluations,
                                 QuestionTopicRepository questionTopics,
                                 EvidencePublisher evidencePublisher,
                                 ApplicationEventPublisher events) {
        this.answers = answers;
        this.humanMarks = humanMarks;
        this.smartMarkResults = smartMarkResults;
        this.agreementEvaluations = agreementEvaluations;
        this.questionTopics = questionTopics;
        this.evidencePublisher = evidencePublisher;
        this.events = events;
    }

    /**
     * Record a human mark for one answer.
     *
     * @param answerId         the answer
     * @param markerId         the teacher
     * @param marksAwarded     authoritative marks (validated ≤ part bound upstream)
     * @param perPointDecisions {markPointId-string: 0|1} point decisions for κ pairing
     * @param comments         free-text rationale
     */
    @Transactional
    public HumanMark recordHumanMark(UUID answerId, UUID markerId, int marksAwarded,
                                     Map<String, Integer> perPointDecisions, String comments) {
        Answer answer = answers.findWithPartAndAttempt(answerId)
                .orElseThrow(() -> new NotFoundException("answer", answerId));
        Attempt attempt = answer.attempt();
        Question question = attempt.question();

        int bound = Math.max(answer.questionPart().marks(), 0);
        if (marksAwarded < 0 || (bound > 0 && marksAwarded > bound)) {
            throw new ConflictException("marks " + marksAwarded
                    + " outside part bound 0–" + bound);
        }

        // an override revises marks; it never re-fires evidence
        boolean revising = attempt.evidenceEmitted();

        if (revising) {
            answer.overridden(marksAwarded);
        } else {
            answer.humanMarked(marksAwarded);
        }
        attempt.humanMarked(revising);
        answers.save(answer);

        List<Answer> attemptAnswers = answers.findByAttemptIdOrderByQuestionPartId(attempt.id());
        attempt.recordTotalMarks(
                attemptAnswers.stream().map(Answer::marksAwarded)
                        .filter(m -> m != null).mapToInt(Integer::intValue).sum(),
                question.marks());

        boolean evidenceFired = false;
        if (!revising) {
            // Fire once, at the authoritative mark that COMPLETES the attempt's
            // marking: a multi-part attempt's total is partial until its last
            // PENDING part is marked, and the settled attempt row must agree
            // with the evidence event (full marks = mastery evidence). Until
            // the marking completes, the evidence waits — fail-closed, exactly
            // like PENDING answers wait for their first authoritative mark.
            if (attemptAnswers.stream().noneMatch(
                    a -> a.markingState() == Answer.MarkingState.PENDING)) {
                List<QuestionTopic> secondary = questionTopics.findByQuestionId(question.id());
                evidenceFired = evidencePublisher.publishGraded(attempt, question, secondary);
            }
        }

        HumanMark mark = humanMarks.save(new HumanMark(
                answer, markerId, marksAwarded, perPointDecisions, comments));
        events.publishEvent(new HumanMarkRecordedEvent(
                answer.id(), attempt.id(), attempt.learnerId(), question.id(),
                marksAwarded, revising, markerId, Instant.now()));
        log.info("human mark recorded for answer {} ({} marks, evidenceFired={}, override={})",
                answerId, marksAwarded, evidenceFired, revising);
        return mark;
    }

    /**
     * Evaluate the κ agreement gate. Scope: one exam paper, or everything.
     *
     * @param examPaperId paper scope, or null for ALL
     * @param computedBy  the requesting teacher
     * @return the persisted evaluation
     */
    @Transactional
    public SmartMarkAgreementEvaluation evaluateAgreement(UUID examPaperId, UUID computedBy) {
        List<HumanMark> humanSample = examPaperId == null
                ? humanMarks.findAllByOrderByCreatedAtAsc()
                : humanMarks.findByPaperOrderByCreatedAtAsc(examPaperId);

        List<int[]> pairs = new ArrayList<>();
        for (HumanMark mark : humanSample) {
            if (mark.perPointDecisions() == null || mark.perPointDecisions().isEmpty()) {
                continue;   // cannot pair without point-level decisions (documented)
            }
            SmartMarkResult smart = smartMarkResults.findLatest(mark.answerId())
                    .filter(SmartMarkResult::validationPassed)
                    .orElse(null);
            if (smart == null || smart.breakdown() == null) {
                continue;
            }
            Map<String, Integer> smartDecisions = new HashMap<>();
            for (Map<String, Object> entry : smart.breakdown()) {
                Object awarded = entry.get("awarded");
                Object pointId = entry.get("markPointId");
                if (pointId != null && awarded instanceof Boolean b) {
                    smartDecisions.put(pointId.toString(), b ? 1 : 0);
                }
            }
            for (Map.Entry<String, Integer> human : mark.perPointDecisions().entrySet()) {
                Integer smartDecision = smartDecisions.get(human.getKey());
                if (smartDecision != null) {
                    pairs.add(new int[]{smartDecision, clampBinary(human.getValue())});
                }
            }
        }
        if (pairs.isEmpty()) {
            throw new ConflictException(
                    "no paired smart/human mark-point decisions available for κ evaluation");
        }

        KappaAgreementService.KappaStats stats = KappaAgreementService.cohenKappa(pairs);
        SmartMarkAgreementEvaluation evaluation = agreementEvaluations.save(
                new SmartMarkAgreementEvaluation(
                        examPaperId == null
                                ? SmartMarkAgreementEvaluation.SCOPE_ALL
                                : SmartMarkAgreementEvaluation.SCOPE_PAPER,
                        examPaperId,
                        stats.sampleSize(),
                        stats.kappa(),
                        stats.observedAgreement(),
                        SmartMarkAgreementEvaluation.DEFAULT_THRESHOLD,
                        computedBy));
        log.info("κ agreement evaluated: scope={} sample={} κ={} (gate {})",
                evaluation.scope(), stats.sampleSize(), stats.kappa(),
                evaluation.passed() ? "PASSED" : "NOT passed");
        return evaluation;
    }

    private static int clampBinary(int value) {
        return value != 0 ? 1 : 0;
    }
}

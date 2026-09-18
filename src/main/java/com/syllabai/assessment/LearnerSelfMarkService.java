package com.syllabai.assessment;

import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.ConflictException;
import com.syllabai.shared.NotFoundException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Learner self-marking (ADR-026 SME practice tranche): after submitting a
 * structured attempt the learner reveals the validated mark scheme and
 * self-awards marks per part, Save-My-Exams-style.
 *
 * <p>The settle/evidence mechanics mirror {@code TeacherMarkingService}
 * .recordHumanMark exactly — attempt-row lock first (the evidence-state
 * concurrency fix), per-part bound checks, once-only evidence publication when
 * the last PENDING part settles, conservative full-marks-equals-correct
 * settlement. Two provenance boundaries differ by design:</p>
 *
 * <ul>
 *   <li>self-marks are recorded in {@code learner_self_marks}, never
 *       {@code HumanMark} — the κ agreement sample stays teacher-only;</li>
 *   <li>self-marking is single-shot: only a caller's OWN structured attempt in
 *       a pre-evidence state (answers PENDING or SMART_MARKED) may be
 *       self-marked. Revising settled marks stays with the teacher override
 *       path — a learner cannot override a teacher mark.</li>
 * </ul>
 */
@Service
public class LearnerSelfMarkService {

    private static final Logger log = LoggerFactory.getLogger(LearnerSelfMarkService.class);

    private final AttemptRepository attempts;
    private final AnswerRepository answers;
    private final QuestionTopicRepository questionTopics;
    private final LearnerSelfMarkRepository selfMarks;
    private final EvidencePublisher evidencePublisher;

    public LearnerSelfMarkService(AttemptRepository attempts,
            AnswerRepository answers,
            QuestionTopicRepository questionTopics,
            LearnerSelfMarkRepository selfMarks,
            EvidencePublisher evidencePublisher) {
        this.attempts = attempts;
        this.answers = answers;
        this.questionTopics = questionTopics;
        this.selfMarks = selfMarks;
        this.evidencePublisher = evidencePublisher;
    }

    /**
     * Self-mark every part of the caller's structured attempt in one shot
     * (the SME flow is reveal → tick → submit, never part-by-part).
     *
     * @throws NotFoundException  unknown attempt, or an attempt owned by
     *                            another learner (no existence leak)
     * @throws BadRequestException non-structured attempt, or a part set that
     *                            does not match the attempt's parts exactly
     * @throws ConflictException  marks outside a part's bound, or the attempt
     *                            already settled (teacher/self-marked)
     */
    @Transactional
    public SelfMarkResult selfMark(UUID learnerId, UUID attemptId,
            Map<UUID, Integer> marksByPartId, String comment) {
        if (marksByPartId == null || marksByPartId.isEmpty()) {
            throw new BadRequestException("self-mark carries no part marks");
        }
        // evidence-state concurrency fix pattern: serialize on the attempt row
        // BEFORE loading any attempt state (TeacherMarkingService precedent)
        Attempt attempt = attempts.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new NotFoundException("attempt", attemptId));
        if (!attempt.learnerId().equals(learnerId)) {
            // no existence leak across learners
            throw new NotFoundException("attempt", attemptId);
        }
        Question question = attempt.question();
        if (question.type() != Question.Type.STRUCTURED) {
            throw new BadRequestException("self-marking applies to structured attempts only");
        }

        List<Answer> attemptAnswers = answers.findByAttemptIdOrderByQuestionPartId(attempt.id());
        if (attemptAnswers.isEmpty()) {
            throw new BadRequestException("attempt carries no part answers");
        }
        for (Answer a : attemptAnswers) {
            Answer.MarkingState s = a.markingState();
            if (s != Answer.MarkingState.PENDING && s != Answer.MarkingState.SMART_MARKED) {
                throw new ConflictException("attempt already settled ("
                        + s + ") — self-marking is single-shot");
            }
        }

        // the request must cover exactly the attempt's parts — no extras,
        // no omissions (SME: one reveal, one full pass)
        Map<UUID, Answer> byPartId = new HashMap<>();
        for (Answer a : attemptAnswers) {
            byPartId.put(a.questionPartId(), a);
        }
        Set<UUID> requested = new HashSet<>(marksByPartId.keySet());
        if (!requested.equals(byPartId.keySet())) {
            throw new BadRequestException("self-mark must cover exactly the attempt's parts");
        }

        for (Map.Entry<UUID, Integer> e : marksByPartId.entrySet()) {
            Answer answer = byPartId.get(e.getKey());
            int bound = Math.max(answer.questionPart().marks(), 0);
            int marks = e.getValue();
            if (marks < 0 || (bound > 0 && marks > bound)) {
                throw new ConflictException("marks " + marks
                        + " outside part bound 0–" + bound);
            }
        }

        // settle: answers first, then the attempt total, then evidence once
        for (Map.Entry<UUID, Integer> e : marksByPartId.entrySet()) {
            Answer answer = byPartId.get(e.getKey());
            answer.selfMarked(e.getValue());
            answers.save(answer);
            selfMarks.save(new LearnerSelfMark(answer, learnerId, e.getValue(), comment));
        }
        attempt.selfMarked();
        attempt.recordTotalMarks(
                attemptAnswers.stream().map(Answer::marksAwarded)
                        .mapToInt(Integer::intValue).sum(),
                question.marks());

        List<QuestionTopic> secondary = questionTopics.findByQuestionId(question.id());
        boolean evidenceFired = evidencePublisher.publishGraded(attempt, question, secondary);

        log.info("learner self-mark recorded for attempt {} ({} parts, evidenceFired={})",
                attemptId, attemptAnswers.size(), evidenceFired);
        return new SelfMarkResult(attempt, attemptAnswers, evidenceFired);
    }

    /** settled snapshot for the response view */
    public record SelfMarkResult(Attempt attempt, List<Answer> answers, boolean evidenceFired) {
    }
}

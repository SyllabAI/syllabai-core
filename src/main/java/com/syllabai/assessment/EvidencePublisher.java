package com.syllabai.assessment;

import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.sme.QuestionSpecPoint;
import com.syllabai.sme.SmeQuestionSpecPointRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Single place where assessment evidence is emitted (Master Spec §12 evidence
 * contract). MCQ attempts publish at submit (existing semantics); structured
 * attempts publish exactly once, at first authoritative marking — guarded by
 * {@link Attempt#markEvidenceEmitted()} so a later human override can never re-run
 * BKT/BDT on the same attempt.
 */
@Component
public class EvidencePublisher {

    private final ApplicationEventPublisher events;
    private final SmeQuestionSpecPointRepository specPoints;

    public EvidencePublisher(ApplicationEventPublisher events,
                             SmeQuestionSpecPointRepository specPoints) {
        this.events = events;
        this.specPoints = specPoints;
    }

    /** MCQ path: publishes immediately at submit (correctness already known). */
    public void publishMcq(Attempt attempt, Question question,
                           List<QuestionTopic> secondaryTopics,
                           List<UUID> expressedMisconceptionIds,
                           List<UUID> observedMisconceptionIds) {
        if (!attempt.markEvidenceEmitted()) {
            throw new IllegalStateException("MCQ attempt submit must fire evidence exactly once");
        }
        emit(attempt, question, secondaryTopics,
                attempt.correct(), attempt.marksAwarded() == null ? 0 : attempt.marksAwarded(),
                expressedMisconceptionIds, observedMisconceptionIds);
    }

    /**
     * Structured path: publishes at first authoritative marking. No-op when evidence
     * already fired (later human overrides revise marks for research, not BKT).
     *
     * @return true when this call emitted the event
     */
    public boolean publishGraded(Attempt attempt, Question question,
                                 List<QuestionTopic> secondaryTopics) {
        if (!attempt.markEvidenceEmitted()) {
            return false;
        }
        int marksTotal = question.marks();
        int marksAwarded = attempt.marksAwarded() == null ? 0 : attempt.marksAwarded();
        // documented correctness rule for partial credit (conservative, BKT-safe):
        // full marks = mastery evidence; partial marks ride along in the event payload
        boolean correct = marksTotal > 0 && marksAwarded >= marksTotal;
        emit(attempt, question, secondaryTopics, correct, marksAwarded, List.of(), List.of());
        return true;
    }

    private void emit(Attempt attempt, Question question, List<QuestionTopic> secondaryTopics,
                      boolean correct, int marksAwarded,
                      List<UUID> expressedIds, List<UUID> observedIds) {
        // null-safe type read (C3 posture at the type level): a real Question entity
        // defaults its type to MCQ_SINGLE, but this publisher is the last line of
        // defense for the submission path — an unexpected null must degrade to the
        // untyped paper-default emission, never 500 the attempt
        Question.Type type = question.type();
        events.publishEvent(new AssessmentEvidenceRecordedEvent(
                attempt.id(), attempt.learnerId(), question.id(),
                topicNodeIds(question, secondaryTopics),
                specPointNodeIds(question),
                correct, question.marks(), marksAwarded,
                attempt.responseTimeMs(), attempt.confidenceLevel(),
                attempt.selfDoubtFlag(), attempt.timedCondition(),
                expressedIds, observedIds,
                attempt.provenance(),
                // S2/ADR-033: the emission context rides the evidence event so the
                // learner model (and the calibration instrument) can price guess per
                // format. MCQ carries the LIVE option count (options are materialized
                // on the grading path); non-MCQ formats carry 0.
                type == null ? null : type.name(),
                type == Question.Type.MCQ_SINGLE ? question.options().size() : 0,
                Instant.now()));
    }

    /**
     * The question's mapped spec-point nodes (T-C18 mapping, ADR-026/V30) —
     * the same marked-attempt evidence carried at spec-point granularity so the
     * learner model maintains skills on the SUBTOPIC nodes the hub's KG paints.
     * Empty on unmapped questions (legacy/teacher-authored): those keep the
     * topic-only firing shape. Deduped; role (PRIMARY/SECONDARY) is content
     * metadata and does not change evidence strength — one marked attempt is
     * one update per node it honestly tests.
     */
    private List<UUID> specPointNodeIds(Question question) {
        List<UUID> ids = new ArrayList<>();
        for (QuestionSpecPoint qsp : specPoints.findByQuestionId(question.id())) {
            UUID nodeId = qsp.specPointNodeId();
            if (nodeId != null && !ids.contains(nodeId)) {
                ids.add(nodeId);
            }
        }
        return ids;
    }

    static List<UUID> topicNodeIds(Question question, List<QuestionTopic> secondaryTopics) {
        List<UUID> ids = new ArrayList<>();
        ids.add(question.primaryTopicNodeId());
        for (QuestionTopic qt : secondaryTopics) {
            if (qt.nodeId() != null && !qt.nodeId().equals(question.primaryTopicNodeId())) {
                ids.add(qt.nodeId());
            }
        }
        return ids;
    }
}

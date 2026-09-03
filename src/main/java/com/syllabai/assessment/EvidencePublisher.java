package com.syllabai.assessment;

import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
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

    public EvidencePublisher(ApplicationEventPublisher events) {
        this.events = events;
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
        events.publishEvent(new AssessmentEvidenceRecordedEvent(
                attempt.id(), attempt.learnerId(), question.id(),
                topicNodeIds(question, secondaryTopics),
                correct, question.marks(), marksAwarded,
                attempt.responseTimeMs(), attempt.confidenceLevel(),
                attempt.selfDoubtFlag(), attempt.timedCondition(),
                expressedIds, observedIds,
                attempt.provenance(), Instant.now()));
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

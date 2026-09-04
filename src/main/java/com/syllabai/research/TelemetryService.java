package com.syllabai.research;

import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.DecayAppliedEvent;
import com.syllabai.shared.events.HumanMarkRecordedEvent;
import com.syllabai.shared.events.MasteryUpdatedEvent;
import com.syllabai.shared.events.MisconceptionUpdatedEvent;
import com.syllabai.shared.events.ReviewScheduledEvent;
import com.syllabai.shared.events.SmartMarkCompletedEvent;
import com.syllabai.shared.events.StruggleInferredEvent;
import com.syllabai.shared.events.TutorAnsweredEvent;
import com.syllabai.shared.events.TutorInterventionSelectedEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Research telemetry ingestion; every domain inference/decision is append-only. */
@Service
public class TelemetryService {
    private final TelemetryEventRepository events;
    public TelemetryService(TelemetryEventRepository events) { this.events = events; }

    @EventListener @Transactional
    public void onAssessmentEvidence(AssessmentEvidenceRecordedEvent e) {
        Map<String,Object> p = new LinkedHashMap<>();
        p.put("attemptId", e.attemptId().toString()); p.put("questionId", e.questionId().toString());
        p.put("correctness", e.correctness()); p.put("marksTotal", e.marksTotal()); p.put("marksAwarded", e.marksAwarded());
        p.put("responseTimeMs", e.responseTimeMs()); p.put("confidence", e.confidence()); p.put("selfDoubtFlag", e.selfDoubtFlag());
        p.put("timedCondition", e.timedCondition()); p.put("topicNodeIds", e.topicNodeIds().stream().map(UUID::toString).toList());
        p.put("misconceptionIds", e.misconceptionIds().stream().map(UUID::toString).toList()); p.put("provenance", e.provenance());
        save(e.learnerId(), TelemetryEvent.Type.ATTEMPT_SUBMITTED, p, e.occurredAt());
        if (e.selfDoubtFlag()) save(e.learnerId(), TelemetryEvent.Type.SELF_DOUBT_FLAGGED,
                Map.of("attemptId", e.attemptId().toString(), "confidence", e.confidence() == null ? -1 : e.confidence()), e.occurredAt());
    }

    @EventListener @Transactional public void onMasteryUpdated(MasteryUpdatedEvent e) {
        save(e.learnerId(), TelemetryEvent.Type.BKT_UPDATED, Map.of("attemptId", e.attemptId().toString(),
                "nodeId", e.nodeId().toString(), "priorMastery", e.priorMastery(), "posteriorMastery", e.posteriorMastery(),
                "correctness", e.correctness(), "attempts", e.attempts(), "correctCount", e.correctCount()), e.occurredAt());
    }
    @EventListener @Transactional public void onMisconceptionUpdated(MisconceptionUpdatedEvent e) {
        save(e.learnerId(), TelemetryEvent.Type.BDT_UPDATED, Map.of("attemptId", e.attemptId().toString(),
                "misconceptionNodeId", e.misconceptionNodeId().toString(), "priorProbability", e.priorProbability(),
                "posteriorProbability", e.posteriorProbability(), "evidence", e.expressed() ? "TAGGED_DISTRACTOR" : "CORRECT_ANSWER"), e.occurredAt());
    }
    @EventListener @Transactional public void onDecayApplied(DecayAppliedEvent e) {
        save(e.learnerId(), TelemetryEvent.Type.DECAY_APPLIED, Map.of("nodeId", e.nodeId().toString(),
                "priorMastery", e.priorMastery(), "decayedMastery", e.decayedMastery(),
                "daysSinceLastPractice", e.daysSinceLastPractice(), "tauDays", e.tauDays()), e.occurredAt());
    }
    @EventListener @Transactional public void onReviewScheduled(ReviewScheduledEvent e) {
        save(e.learnerId(), TelemetryEvent.Type.REVIEW_SCHEDULED, Map.of("nodeId", e.nodeId().toString(),
                "dueAt", e.dueAt().toString(), "masteryAtTrigger", e.masteryAtTrigger(), "reason", e.reason()), e.occurredAt());
    }
    @EventListener @Transactional public void onSmartMarkCompleted(SmartMarkCompletedEvent e) {
        save(e.learnerId(), TelemetryEvent.Type.SMART_MARK_COMPLETED, Map.of("answerId", e.answerId().toString(),
                "attemptId", e.attemptId().toString(), "questionId", e.questionId().toString(), "marksAwarded", e.marksAwarded(),
                "marksPossible", e.marksPossible(), "validationPassed", e.validationPassed(), "pipelineVersion", e.pipelineVersion(),
                "authoritative", e.authoritative()), e.occurredAt());
    }
    @EventListener @Transactional public void onHumanMarkRecorded(HumanMarkRecordedEvent e) {
        save(e.learnerId(), TelemetryEvent.Type.HUMAN_MARK_RECORDED, Map.of("answerId", e.answerId().toString(),
                "attemptId", e.attemptId().toString(), "questionId", e.questionId().toString(), "marksAwarded", e.marksAwarded(),
                "revising", e.revising(), "markerId", e.markerId().toString()), e.occurredAt());
    }
    @EventListener @Transactional public void onTutorAnswered(TutorAnsweredEvent e) {
        Map<String,Object> p = new LinkedHashMap<>(); p.put("question", e.question());
        p.put("matchedTopicIds", e.matchedTopicIds().stream().map(UUID::toString).toList()); p.put("evidenceCount", e.evidenceCount());
        p.put("evidenceSources", e.evidenceSources()); p.put("refused", e.refused()); p.put("answerModel", e.answerModel());
        p.put("promptVersion", e.promptVersion()); p.put("latencyMs", e.latencyMs()); p.put("provenance", "ka-rag-pipeline/1.0.0");
        save(e.learnerId() == null ? new UUID(0,0) : e.learnerId(), TelemetryEvent.Type.KA_RAG_COMPLETED, p, e.occurredAt());
    }
    @EventListener @Transactional public void onStruggleInferred(StruggleInferredEvent e) {
        Map<String,Object> p = new LinkedHashMap<>(); p.put("topicNodeId", e.topicNodeId().toString()); p.put("type", e.type().name());
        p.put("subtype", e.subtype()); p.put("probability", e.probability()); p.put("supportingEvidence", e.supportingEvidence());
        p.put("modelVersion", e.modelVersion()); save(e.learnerId(), TelemetryEvent.Type.STRUGGLE_INFERRED, p, e.occurredAt());
    }
    @EventListener @Transactional public void onTutorInterventionSelected(TutorInterventionSelectedEvent e) {
        save(e.learnerId(), TelemetryEvent.Type.TUTOR_INTERVENTION_SELECTED, Map.of(
                "topicNodeIds", e.topicNodeIds().stream().map(UUID::toString).toList(),
                "interventionType", e.interventionType(), "rationale", e.rationale(), "policyVersion", e.policyVersion()), e.occurredAt());
    }
    private void save(UUID learnerId, TelemetryEvent.Type type, Map<String,Object> payload, Instant at) {
        events.save(new TelemetryEvent(learnerId, type, payload, at));
    }
}

package com.syllabai.research;

import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.DecayAppliedEvent;
import com.syllabai.shared.events.MasteryUpdatedEvent;
import com.syllabai.shared.events.MisconceptionUpdatedEvent;
import com.syllabai.shared.events.ReviewScheduledEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Research telemetry ingestion (Master Spec §18): observes learner-model domain events
 * and appends immutable learning-log rows. The full Cycle-1 event stream is covered:
 * ATTEMPT_SUBMITTED, BKT_UPDATED, BDT_UPDATED, REVIEW_SCHEDULED, DECAY_APPLIED and
 * SELF_DOUBT_FLAGGED. Paper B §3.5 keystroke-timing fields arrive with the frontend
 * instrumentation (Wave 2, T-020 follow-up); the v0 payload captures the
 * server-observed core.
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final TelemetryEventRepository events;

    public TelemetryService(TelemetryEventRepository events) {
        this.events = events;
    }

    @EventListener
    @Transactional
    public void onAssessmentEvidence(AssessmentEvidenceRecordedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", event.attemptId().toString());
        payload.put("questionId", event.questionId().toString());
        payload.put("correctness", event.correctness());
        payload.put("marksTotal", event.marksTotal());
        payload.put("marksAwarded", event.marksAwarded());
        payload.put("responseTimeMs", event.responseTimeMs());
        payload.put("confidence", event.confidence());
        payload.put("selfDoubtFlag", event.selfDoubtFlag());
        payload.put("timedCondition", event.timedCondition());
        payload.put("topicNodeIds", event.topicNodeIds().stream().map(UUID::toString).toList());
        payload.put("misconceptionIds", event.misconceptionIds().stream().map(UUID::toString).toList());
        payload.put("observedMisconceptionIds",
                event.observedMisconceptionIds().stream().map(UUID::toString).toList());
        payload.put("provenance", event.provenance());

        events.save(new TelemetryEvent(
                event.learnerId(),
                TelemetryEvent.Type.ATTEMPT_SUBMITTED,
                payload,
                event.occurredAt()));
        if (event.selfDoubtFlag()) {
            events.save(new TelemetryEvent(
                    event.learnerId(),
                    TelemetryEvent.Type.SELF_DOUBT_FLAGGED,
                    Map.of("attemptId", event.attemptId().toString(),
                           "confidence", event.confidence() == null ? -1 : event.confidence()),
                    event.occurredAt()));
        }
        log.debug("telemetry appended for attempt {}", event.attemptId());
    }

    @EventListener
    @Transactional
    public void onMasteryUpdated(MasteryUpdatedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", event.attemptId().toString());
        payload.put("nodeId", event.nodeId().toString());
        payload.put("priorMastery", event.priorMastery());
        payload.put("posteriorMastery", event.posteriorMastery());
        payload.put("correctness", event.correctness());
        payload.put("attempts", event.attempts());
        payload.put("correctCount", event.correctCount());
        events.save(new TelemetryEvent(
                event.learnerId(), TelemetryEvent.Type.BKT_UPDATED, payload, event.occurredAt()));
        log.debug("BKT_UPDATED telemetry appended for learner {} node {}",
                event.learnerId(), event.nodeId());
    }

    @EventListener
    @Transactional
    public void onMisconceptionUpdated(MisconceptionUpdatedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("attemptId", event.attemptId().toString());
        payload.put("misconceptionNodeId", event.misconceptionNodeId().toString());
        payload.put("priorProbability", event.priorProbability());
        payload.put("posteriorProbability", event.posteriorProbability());
        payload.put("evidence", event.expressed() ? "TAGGED_DISTRACTOR" : "CORRECT_ANSWER");
        events.save(new TelemetryEvent(
                event.learnerId(), TelemetryEvent.Type.BDT_UPDATED, payload, event.occurredAt()));
        log.debug("BDT_UPDATED telemetry appended for learner {} misconception {}",
                event.learnerId(), event.misconceptionNodeId());
    }

    @EventListener
    @Transactional
    public void onDecayApplied(DecayAppliedEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", event.nodeId().toString());
        payload.put("priorMastery", event.priorMastery());
        payload.put("decayedMastery", event.decayedMastery());
        payload.put("daysSinceLastPractice", event.daysSinceLastPractice());
        payload.put("tauDays", event.tauDays());
        payload.put("reviewThresholdCrossed", event.reviewThresholdCrossed());
        events.save(new TelemetryEvent(
                event.learnerId(), TelemetryEvent.Type.DECAY_APPLIED, payload, event.occurredAt()));
        log.debug("DECAY_APPLIED telemetry appended for learner {} node {}",
                event.learnerId(), event.nodeId());
    }

    @EventListener
    @Transactional
    public void onReviewScheduled(ReviewScheduledEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", event.nodeId().toString());
        payload.put("dueAt", event.dueAt().toString());
        payload.put("masteryAtTrigger", event.masteryAtTrigger());
        payload.put("reason", event.reason());
        events.save(new TelemetryEvent(
                event.learnerId(), TelemetryEvent.Type.REVIEW_SCHEDULED, payload, event.occurredAt()));
        log.debug("REVIEW_SCHEDULED telemetry appended for learner {} node {}",
                event.learnerId(), event.nodeId());
    }
}

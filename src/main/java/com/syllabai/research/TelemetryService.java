package com.syllabai.research;

import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.DecayAppliedEvent;
import com.syllabai.shared.events.HumanMarkRecordedEvent;
import com.syllabai.shared.events.MasteryUpdatedEvent;
import com.syllabai.shared.events.MisconceptionUpdatedEvent;
import com.syllabai.shared.events.ReviewScheduledEvent;
import com.syllabai.shared.events.SmartMarkCompletedEvent;
import com.syllabai.shared.events.TutorAnsweredEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * research telemetry ingestion: observes learner-model domain events
 * and appends immutable learning-log rows. The full Cycle-1 event stream is covered:
 * ATTEMPT_SUBMITTED, BKT_UPDATED, BDT_UPDATED, REVIEW_SCHEDULED, DECAY_APPLIED and
 * SELF_DOUBT_FLAGGED, plus the V9 marking-loop extension (SMART_MARK_COMPLETED,
 * HUMAN_MARK_RECORDED — Master Spec §15 calibration dataset). Paper B §3.5
 * keystroke-timing fields arrive with the frontend instrumentation (Wave 2, T-020
 * follow-up); the v0 payload captures the server-observed core.
 *
 * <p>Dispatch posture (audit M1, 2026-10-02): every handler is {@code @Async}
 * on the {@code telemetryExecutor} ({@link TelemetryDispatchConfig}) AND
 * guards its own body — telemetry is an append-only research record and must
 * never fail the serving path that triggered it. A database hiccup during a
 * write drops that row with a WARN; it never turns a fully-generated answer,
 * a marked attempt or a CLA exchange into a 5xx. In production the executor
 * is a bounded pool (writes leave the request thread entirely); the {@code it}
 * profile pins {@code syllabai.telemetry.dispatch=sync} so flow ITs can
 * assert rows deterministically right after the act (the pre-M1 inline
 * dispatch, kept as an explicit test seam).</p>
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);

    private final TelemetryEventRepository events;

    public TelemetryService(TelemetryEventRepository events) {
        this.events = events;
    }

    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onAssessmentEvidence(AssessmentEvidenceRecordedEvent event) {
        try {
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
        } catch (RuntimeException e) {
            log.warn("ATTEMPT_SUBMITTED telemetry dropped — the research record "
                    + "must never fail the attempt path", e);
        }
    }

    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onMasteryUpdated(MasteryUpdatedEvent event) {
        try {
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
        } catch (RuntimeException e) {
            log.warn("BKT_UPDATED telemetry dropped — the research record "
                    + "must never fail the attempt path", e);
        }
    }

    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onMisconceptionUpdated(MisconceptionUpdatedEvent event) {
        try {
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
        } catch (RuntimeException e) {
            log.warn("BDT_UPDATED telemetry dropped — the research record "
                    + "must never fail the attempt path", e);
        }
    }

    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onDecayApplied(DecayAppliedEvent event) {
        try {
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
        } catch (RuntimeException e) {
            log.warn("DECAY_APPLIED telemetry dropped — the research record "
                    + "must never fail the attempt path", e);
        }
    }

    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onReviewScheduled(ReviewScheduledEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", event.nodeId().toString());
            payload.put("dueAt", event.dueAt().toString());
            payload.put("masteryAtTrigger", event.masteryAtTrigger());
            payload.put("reason", event.reason());
            events.save(new TelemetryEvent(
                    event.learnerId(), TelemetryEvent.Type.REVIEW_SCHEDULED, payload, event.occurredAt()));
            log.debug("REVIEW_SCHEDULED telemetry appended for learner {} node {}",
                    event.learnerId(), event.nodeId());
        } catch (RuntimeException e) {
            log.warn("REVIEW_SCHEDULED telemetry dropped — the research record "
                    + "must never fail the attempt path", e);
        }
    }

    /** V9 marking loop: every Smart Mark run lands in the research record (§15). */
    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onSmartMarkCompleted(SmartMarkCompletedEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answerId", event.answerId().toString());
            payload.put("attemptId", event.attemptId().toString());
            payload.put("questionId", event.questionId().toString());
            payload.put("marksAwarded", event.marksAwarded());
            payload.put("marksPossible", event.marksPossible());
            payload.put("validationPassed", event.validationPassed());
            if (event.failureReason() != null) {
                payload.put("failureReason", event.failureReason());
            }
            if (event.modelId() != null) {
                payload.put("modelId", event.modelId());
            }
            payload.put("pipelineVersion", event.pipelineVersion());
            payload.put("authoritative", event.authoritative());
            events.save(new TelemetryEvent(
                    event.learnerId(), TelemetryEvent.Type.SMART_MARK_COMPLETED,
                    payload, event.occurredAt()));
            log.debug("SMART_MARK_COMPLETED telemetry appended for answer {}", event.answerId());
        } catch (RuntimeException e) {
            log.warn("SMART_MARK_COMPLETED telemetry dropped — the research record "
                    + "must never fail the marking path", e);
        }
    }

    /** V9 marking loop: human marks and overrides are research-visible (§15). */
    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onHumanMarkRecorded(HumanMarkRecordedEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answerId", event.answerId().toString());
            payload.put("attemptId", event.attemptId().toString());
            payload.put("questionId", event.questionId().toString());
            payload.put("marksAwarded", event.marksAwarded());
            payload.put("revising", event.revising());
            payload.put("markerId", event.markerId().toString());
            events.save(new TelemetryEvent(
                    event.learnerId(), TelemetryEvent.Type.HUMAN_MARK_RECORDED,
                    payload, event.occurredAt()));
            log.debug("HUMAN_MARK_RECORDED telemetry appended for answer {}", event.answerId());
        } catch (RuntimeException e) {
            log.warn("HUMAN_MARK_RECORDED telemetry dropped — the research record "
                    + "must never fail the marking path", e);
        }
    }

    /**
     * KA-RAG chat-exchange record (T-024, Paper B §3.5): what was asked, which
     * evidence grounded the answer, which model/prompt answered, and whether
     * the pipeline refused (empty evidence). Append-only; anonymous previews
     * are logged with a null learner under the reserved
     * 00000000-0000-0000-0000-000000000000 id to keep the FK-shaped column.
     */
    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onTutorAnswered(TutorAnsweredEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", event.question());
            payload.put("matchedTopicIds",
                    event.matchedTopicIds().stream().map(UUID::toString).toList());
            payload.put("evidenceCount", event.evidenceCount());
            payload.put("evidenceSources", event.evidenceSources());
            payload.put("refused", event.refused());
            payload.put("answerModel", event.answerModel() == null ? "" : event.answerModel());
            // D2: persist the deterministic refusal provider — production telemetry
            // distinguishes "deterministic-refusal" (the grounding gate) from
            // "deterministic-paper-refusal" (the fail-open guard's zeroed-pool
            // refusal); on a grounded answer this is the generator's provider name
            payload.put("answerProvider", event.answerProvider() == null ? "" : event.answerProvider());
            payload.put("promptVersion", event.promptVersion());
            payload.put("latencyMs", event.latencyMs());
            payload.put("historyTurns", event.historyTurns());
            payload.put("sessionId", event.sessionId() == null ? "" : event.sessionId().toString());
            payload.put("provenance", "ka-rag-pipeline/1.0.0");
            events.save(new TelemetryEvent(
                    event.learnerId() == null ? new UUID(0, 0) : event.learnerId(),
                    TelemetryEvent.Type.KA_RAG_COMPLETED,
                    payload,
                    event.occurredAt()));
        } catch (RuntimeException e) {
            log.warn("KA_RAG_COMPLETED telemetry dropped — the research record "
                    + "must never fail the ask path", e);
        }
    }

    /**
     * CLA exchange record (V24, CLA contract §4.4 + §6): the contextual
     * counterpart of the KA-RAG chat-exchange record. Append-only research
     * telemetry — the question text and the read-only tool invocation trace
     * (tool, arguments reference, result size, latency) live ONLY here, never
     * in learner memory. Anonymous/defensive null learners land under the
     * reserved zero id, mirroring the tutor handler.
     */
    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onClaInteraction(com.syllabai.shared.events.ClaInteractionEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("question", event.question());
            payload.put("mode", event.responseMode());
            payload.put("contextKind", event.contextKind());
            payload.put("contextReference", event.contextReference() == null
                    ? "" : event.contextReference().toString());
            payload.put("matchedTopicIds",
                    event.matchedTopicIds().stream().map(UUID::toString).toList());
            payload.put("evidenceCount", event.evidenceCount());
            payload.put("evidenceSources", event.evidenceSources());
            payload.put("refused", event.refused());
            payload.put("answerModel", event.answerModel() == null ? "" : event.answerModel());
            payload.put("promptVersion", event.promptVersion());
            payload.put("latencyMs", event.latencyMs());
            payload.put("provenance", "cla-contextual/1.0.0");
            payload.put("tools", event.tools() == null ? List.of()
                    : event.tools().stream().map(t -> {
                        Map<String, Object> tool = new LinkedHashMap<>();
                        tool.put("tool", t.tool());
                        tool.put("args", t.args());
                        tool.put("resultSize", t.resultSize());
                        tool.put("latencyMs", t.latencyMs());
                        return tool;
                    }).toList());
            events.save(new TelemetryEvent(
                    event.learnerId() == null ? new UUID(0, 0) : event.learnerId(),
                    TelemetryEvent.Type.CLA_EXCHANGE_COMPLETED,
                    payload,
                    event.occurredAt()));
        } catch (RuntimeException e) {
            log.warn("CLA_EXCHANGE_COMPLETED telemetry dropped — the research record "
                    + "must never fail the ask path", e);
        }
    }

    /**
     * Student Smart Mark feedback actions (F-047 learner half): consumption of
     * the ephemeral "Explain my feedback" / "Improve my answer" generations.
     * The prose itself is never persisted — the accepted SmartMarkResult is the
     * durable artifact; this event records only that the explanation/plan was
     * consumed, with which grounding result and which model.
     */
    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onSmartFeedbackExplained(com.syllabai.shared.events.SmartFeedbackExplainedEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answerId", event.answerId().toString());
            payload.put("attemptId", event.attemptId().toString());
            payload.put("questionId", event.questionId().toString());
            payload.put("smartMarkResultId", event.smartMarkResultId().toString());
            payload.put("answerModel", event.modelId() == null ? "" : event.modelId());
            payload.put("provenance", "smart-mark-feedback/1.0.0");
            events.save(new TelemetryEvent(
                    event.learnerId(), TelemetryEvent.Type.SMART_FEEDBACK_EXPLAINED,
                    payload, event.occurredAt()));
        } catch (RuntimeException e) {
            log.warn("SMART_FEEDBACK_EXPLAINED telemetry dropped — the research record "
                    + "must never fail the learner path", e);
        }
    }

    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener
    @Transactional
    public void onSmartImprovementPlanViewed(
            com.syllabai.shared.events.SmartImprovementPlanViewedEvent event) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answerId", event.answerId().toString());
            payload.put("attemptId", event.attemptId().toString());
            payload.put("questionId", event.questionId().toString());
            payload.put("smartMarkResultId", event.smartMarkResultId().toString());
            payload.put("answerModel", event.modelId() == null ? "" : event.modelId());
            payload.put("provenance", "smart-mark-feedback/1.0.0");
            events.save(new TelemetryEvent(
                    event.learnerId(), TelemetryEvent.Type.SMART_IMPROVEMENT_PLAN_VIEWED,
                    payload, event.occurredAt()));
        } catch (RuntimeException e) {
            log.warn("SMART_IMPROVEMENT_PLAN_VIEWED telemetry dropped — the research record "
                    + "must never fail the learner path", e);
        }
    }
}

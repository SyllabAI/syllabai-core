package com.syllabai.research;

import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Research telemetry ingestion (Master Spec §18): observes assessment evidence and
 * appends immutable learning-log events. Paper B §3.5 keystroke-timing fields arrive
 * with the frontend instrumentation (Wave 2, T-020); the v0 payload captures the
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
}

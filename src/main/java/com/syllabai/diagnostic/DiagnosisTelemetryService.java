package com.syllabai.diagnostic;

import com.syllabai.shared.events.StruggleInferredEvent;
import com.syllabai.shared.events.TutorInterventionSelectedEvent;
import com.syllabai.research.TelemetryDispatchConfig;
import com.syllabai.research.TelemetryEvent;
import com.syllabai.research.TelemetryEventRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Research-only telemetry observer for T-026/T-027; keeps the existing telemetry service unchanged.
 * Anonymous selections (learnerId == null) are recorded under the reserved
 * all-zero id, exactly like {@code TelemetryService#onTutorAnswered}.
 *
 * <p>Dispatch posture (audit M1, 2026-10-02): mirrors
 * {@code TelemetryService} — {@code @Async} on the shared
 * {@code telemetryExecutor} and a guarded body, so the research record can
 * never fail the diagnostic flow that triggered it (a failed write drops the
 * row with a WARN, it never 5xxs a session). The {@code it} profile pins the
 * inline {@code sync} dispatch for deterministic flow assertions.</p> */
@Service
public class DiagnosisTelemetryService {
    private static final Logger log = LoggerFactory.getLogger(DiagnosisTelemetryService.class);

    /** reserved id for anonymous/preview records — keeps the FK-shaped column NOT NULL */
    static final UUID ANONYMOUS_LEARNER = new UUID(0, 0);

    private final TelemetryEventRepository events;
    public DiagnosisTelemetryService(TelemetryEventRepository events) { this.events = events; }

    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener @Transactional
    public void onStruggleInferred(StruggleInferredEvent e) {
        try {
            Map<String,Object> p = new LinkedHashMap<>();
            p.put("topicNodeId", e.topicNodeId().toString()); p.put("type", e.type().name());
            p.put("subtype", e.subtype()); p.put("probability", e.probability());
            p.put("supportingEvidence", e.supportingEvidence()); p.put("modelVersion", e.modelVersion());
            events.save(new TelemetryEvent(e.learnerId(), TelemetryEvent.Type.STRUGGLE_INFERRED, p, e.occurredAt()));
        } catch (RuntimeException ex) {
            log.warn("STRUGGLE_INFERRED telemetry dropped — the research record "
                    + "must never fail the diagnostic path", ex);
        }
    }

    @Async(TelemetryDispatchConfig.EXECUTOR)
    @EventListener @Transactional
    public void onTutorInterventionSelected(TutorInterventionSelectedEvent e) {
        try {
            events.save(new TelemetryEvent(
                    e.learnerId() == null ? ANONYMOUS_LEARNER : e.learnerId(),
                    TelemetryEvent.Type.TUTOR_INTERVENTION_SELECTED,
                    Map.of("topicNodeIds", e.topicNodeIds().stream().map(Object::toString).toList(),
                            "interventionType", e.interventionType(), "rationale", e.rationale(),
                            "policyVersion", e.policyVersion()), e.occurredAt()));
        } catch (RuntimeException ex) {
            log.warn("TUTOR_INTERVENTION_SELECTED telemetry dropped — the research record "
                    + "must never fail the diagnostic path", ex);
        }
    }
}

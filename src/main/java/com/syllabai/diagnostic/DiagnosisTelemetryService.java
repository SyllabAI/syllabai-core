package com.syllabai.diagnostic;

import com.syllabai.shared.events.StruggleInferredEvent;
import com.syllabai.shared.events.TutorInterventionSelectedEvent;
import com.syllabai.research.TelemetryEvent;
import com.syllabai.research.TelemetryEventRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Research-only telemetry observer for T-026/T-027; keeps the existing telemetry service unchanged. */
@Service
public class DiagnosisTelemetryService {
    private final TelemetryEventRepository events;
    public DiagnosisTelemetryService(TelemetryEventRepository events) { this.events = events; }

    @EventListener @Transactional
    public void onStruggleInferred(StruggleInferredEvent e) {
        Map<String,Object> p = new LinkedHashMap<>();
        p.put("topicNodeId", e.topicNodeId().toString()); p.put("type", e.type().name());
        p.put("subtype", e.subtype()); p.put("probability", e.probability());
        p.put("supportingEvidence", e.supportingEvidence()); p.put("modelVersion", e.modelVersion());
        events.save(new TelemetryEvent(e.learnerId(), TelemetryEvent.Type.STRUGGLE_INFERRED, p, e.occurredAt()));
    }

    @EventListener @Transactional
    public void onTutorInterventionSelected(TutorInterventionSelectedEvent e) {
        events.save(new TelemetryEvent(e.learnerId(), TelemetryEvent.Type.TUTOR_INTERVENTION_SELECTED,
                Map.of("topicNodeIds", e.topicNodeIds().stream().map(Object::toString).toList(),
                        "interventionType", e.interventionType(), "rationale", e.rationale(),
                        "policyVersion", e.policyVersion()), e.occurredAt()));
    }
}

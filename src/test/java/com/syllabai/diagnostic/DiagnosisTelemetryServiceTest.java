package com.syllabai.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.research.TelemetryEvent;
import com.syllabai.research.TelemetryEventRepository;
import com.syllabai.shared.events.StruggleInferredEvent;
import com.syllabai.shared.events.TutorInterventionSelectedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;

/**
 * Research telemetry for T-026/T-027: both event families persist with
 * model/policy versions, and anonymous selections are recorded under the
 * reserved all-zero learner id (never a NOT NULL failure).
 */
class DiagnosisTelemetryServiceTest {

    private final TelemetryEventRepository events = mock(TelemetryEventRepository.class);
    private final DiagnosisTelemetryService service = new DiagnosisTelemetryService(events);

    private final UUID learnerId = UUID.randomUUID();
    private final Instant occurredAt = Instant.parse("2026-09-04T12:00:00Z");

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("STRUGGLE_INFERRED persists with evidence, model version and timestamp")
    void struggleInferredRecorded() {
        service.onStruggleInferred(new StruggleInferredEvent(
                learnerId, UUID.randomUUID(), StruggleType.PREREQUISITE_GAP,
                "1a_weak_prerequisite_mastery", 0.75,
                Map.of("signal", "weak_prerequisite_mastery", "weakest_mastery", 0.1),
                "rules-v0.2", occurredAt));

        ArgumentCaptor<TelemetryEvent> captor = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(events).save(captor.capture());
        TelemetryEvent saved = captor.getValue();
        assertThat(saved.type()).isEqualTo(TelemetryEvent.Type.STRUGGLE_INFERRED);
        assertThat(saved.learnerId()).isEqualTo(learnerId);
        assertThat(saved.occurredAt()).isEqualTo(occurredAt);
        assertThat(saved.payload())
                .containsEntry("type", "PREREQUISITE_GAP")
                .containsEntry("subtype", "1a_weak_prerequisite_mastery")
                .containsEntry("probability", 0.75)
                .containsEntry("modelVersion", "rules-v0.2");
        assertThat(saved.payload()).containsKey("supportingEvidence");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("TUTOR_INTERVENTION_SELECTED persists with policy version and rationale")
    void interventionSelectedRecorded() {
        service.onTutorInterventionSelected(new TutorInterventionSelectedEvent(
                learnerId, List.of(UUID.randomUUID()), "PREREQUISITE_REVIEW",
                "1a_weak_prerequisite_mastery", "rules-v0.2", occurredAt));

        ArgumentCaptor<TelemetryEvent> captor = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(events).save(captor.capture());
        TelemetryEvent saved = captor.getValue();
        assertThat(saved.type()).isEqualTo(TelemetryEvent.Type.TUTOR_INTERVENTION_SELECTED);
        assertThat(saved.learnerId()).isEqualTo(learnerId);
        assertThat(saved.payload())
                .containsEntry("interventionType", "PREREQUISITE_REVIEW")
                .containsEntry("policyVersion", "rules-v0.2")
                .containsKey("topicNodeIds")
                .containsKey("rationale");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("anonymous selection is recorded under the reserved id, never null (NOT NULL column)")
    void anonymousUsesReservedId() {
        service.onTutorInterventionSelected(new TutorInterventionSelectedEvent(
                null, List.of(), "EXPLANATION", "anonymous request", "rules-v0.2", occurredAt));

        ArgumentCaptor<TelemetryEvent> captor = ArgumentCaptor.forClass(TelemetryEvent.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().learnerId()).isEqualTo(new UUID(0, 0));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("reserved anonymous id equals TelemetryService's convention")
    void reservedIdMatchesConvention() {
        assertThat(DiagnosisTelemetryService.ANONYMOUS_LEARNER)
                .isEqualTo(new UUID(0, 0));
    }
}

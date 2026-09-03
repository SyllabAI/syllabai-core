package com.syllabai.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.DecayAppliedEvent;
import com.syllabai.shared.events.MasteryUpdatedEvent;
import com.syllabai.shared.events.MisconceptionUpdatedEvent;
import com.syllabai.shared.events.ReviewScheduledEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Full Cycle-1 telemetry coverage (audit fix #3): every declared event type in the
 * V5 schema must actually be emitted — ATTEMPT_SUBMITTED, BKT_UPDATED, BDT_UPDATED,
 * REVIEW_SCHEDULED, DECAY_APPLIED, SELF_DOUBT_FLAGGED.
 */
class TelemetryServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final UUID QUESTION = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID MISCONCEPTION = UUID.randomUUID();
    private static final Instant WHEN = Instant.parse("2026-09-03T12:00:00Z");

    private final TelemetryEventRepository events = mock(TelemetryEventRepository.class);
    private final TelemetryService service = new TelemetryService(events);

    private final ArgumentCaptor<TelemetryEvent> saved = forClass(TelemetryEvent.class);

    private AssessmentEvidenceRecordedEvent evidence(boolean selfDoubt) {
        return new AssessmentEvidenceRecordedEvent(
                ATTEMPT, LEARNER, QUESTION, List.of(NODE), false, 1, 0,
                1000L, 3, selfDoubt, false,
                List.of(MISCONCEPTION), List.of(MISCONCEPTION), "test", WHEN);
    }

    @Test
    @DisplayName("attempt evidence appends ATTEMPT_SUBMITTED with the full v0 payload")
    void attemptSubmitted() {
        service.onAssessmentEvidence(evidence(false));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.ATTEMPT_SUBMITTED);
        assertThat(row.learnerId()).isEqualTo(LEARNER);
        assertThat(row.occurredAt()).isEqualTo(WHEN);
        assertThat(row.payload())
                .containsEntry("correctness", false)
                .containsEntry("responseTimeMs", 1000L)
                .containsEntry("provenance", "test")
                .containsKey("observedMisconceptionIds");
    }

    @Test
    @DisplayName("self-doubt flag additionally appends SELF_DOUBT_FLAGGED")
    void selfDoubtFlagged() {
        service.onAssessmentEvidence(evidence(true));

        verify(events, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(TelemetryEvent::type)
                .containsExactly(TelemetryEvent.Type.ATTEMPT_SUBMITTED,
                                 TelemetryEvent.Type.SELF_DOUBT_FLAGGED);
    }

    @Test
    @DisplayName("BKT updates append BKT_UPDATED with prior/posterior")
    void bktUpdated() {
        service.onMasteryUpdated(new MasteryUpdatedEvent(
                LEARNER, ATTEMPT, NODE, 0.1131, 0.3832, true, 1, 1, WHEN));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.BKT_UPDATED);
        assertThat(row.payload())
                .containsEntry("nodeId", NODE.toString())
                .containsEntry("priorMastery", 0.1131)
                .containsEntry("posteriorMastery", 0.3832)
                .containsEntry("correctness", true);
    }

    @Test
    @DisplayName("BDT updates append BDT_UPDATED with the evidence kind")
    void bdtUpdated() {
        service.onMisconceptionUpdated(new MisconceptionUpdatedEvent(
                LEARNER, ATTEMPT, MISCONCEPTION, 0.75, 0.5, false, WHEN));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.BDT_UPDATED);
        assertThat(row.payload())
                .containsEntry("misconceptionNodeId", MISCONCEPTION.toString())
                .containsEntry("priorProbability", 0.75)
                .containsEntry("posteriorProbability", 0.5)
                .containsEntry("evidence", "CORRECT_ANSWER");
    }

    @Test
    @DisplayName("decay applications append DECAY_APPLIED with tau and review flag")
    void decayApplied() {
        service.onDecayApplied(new DecayAppliedEvent(
                LEARNER, NODE, 0.5, 0.3206, 40, 90, true, WHEN));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.DECAY_APPLIED);
        assertThat(row.payload())
                .containsEntry("priorMastery", 0.5)
                .containsEntry("decayedMastery", 0.3206)
                .containsEntry("daysSinceLastPractice", 40L)
                .containsEntry("tauDays", 90)
                .containsEntry("reviewThresholdCrossed", true);
    }

    @Test
    @DisplayName("review scheduling appends REVIEW_SCHEDULED with reason and trigger mastery")
    void reviewScheduled() {
        service.onReviewScheduled(new ReviewScheduledEvent(
                LEARNER, NODE, WHEN, 0.3206, "DECAY_CROSSED_THRESHOLD", WHEN));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.REVIEW_SCHEDULED);
        assertThat(row.payload())
                .containsEntry("nodeId", NODE.toString())
                .containsEntry("masteryAtTrigger", 0.3206)
                .containsEntry("reason", "DECAY_CROSSED_THRESHOLD")
                .containsEntry("dueAt", WHEN.toString());
    }
}

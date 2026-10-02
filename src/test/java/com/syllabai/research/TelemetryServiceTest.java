package com.syllabai.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.DecayAppliedEvent;
import com.syllabai.shared.events.HumanMarkRecordedEvent;
import com.syllabai.shared.events.MasteryUpdatedEvent;
import com.syllabai.shared.events.MisconceptionUpdatedEvent;
import com.syllabai.shared.events.ReviewScheduledEvent;
import com.syllabai.shared.events.SmartMarkCompletedEvent;
import com.syllabai.shared.events.TutorAnsweredEvent;
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
                ATTEMPT, LEARNER, QUESTION, List.of(NODE), List.of(), false, 1, 0,
                1000L, 3, selfDoubt, false,
                List.of(MISCONCEPTION), List.of(MISCONCEPTION), "test", null, 0, WHEN);
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
    @DisplayName("attempt evidence with the OPTIONAL confidence null still appends (regression: Map.copyOf NPE 500'd POST /attempts)")
    void attemptSubmittedWithoutConfidence() {
        service.onAssessmentEvidence(new AssessmentEvidenceRecordedEvent(
                ATTEMPT, LEARNER, QUESTION, List.of(NODE), List.of(), false, 1, 0,
                1000L, null, false, false,
                List.of(MISCONCEPTION), List.of(MISCONCEPTION), "test", null, 0, WHEN));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.ATTEMPT_SUBMITTED);
        assertThat(row.payload()).containsEntry("confidence", null);
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
    @DisplayName("BKT updates append BKT_UPDATED with prior/posterior, the decayed forecast, gap and format context")
    void bktUpdated() {
        service.onMasteryUpdated(new MasteryUpdatedEvent(
                LEARNER, ATTEMPT, NODE, 0.9, 0.8331, true, 1, 1,
                0.5496, 180L, "MCQ_SINGLE", 4, WHEN));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.BKT_UPDATED);
        assertThat(row.payload())
                .containsEntry("nodeId", NODE.toString())
                .containsEntry("priorMastery", 0.9)
                .containsEntry("posteriorMastery", 0.8331)
                .containsEntry("correctness", true)
                // S2 challenge C1: the pre-attempt forecast + gap ride beside the anchor
                .containsEntry("decayedPrior", 0.5496)
                .containsEntry("gapDays", 180L)
                // C2: the emission context — the report prices the predicted emission per row
                .containsEntry("questionType", "MCQ_SINGLE")
                .containsEntry("optionCount", 4);
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

    @Test
    @DisplayName("smart-mark completion appends SMART_MARK_COMPLETED with model provenance")
    void smartMarkCompleted() {
        UUID answer = UUID.randomUUID();
        service.onSmartMarkCompleted(new SmartMarkCompletedEvent(
                answer, ATTEMPT, LEARNER, QUESTION, 2, 3, true, null,
                "llama-3.3-70b-versatile", "1.0.0", false, WHEN));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.SMART_MARK_COMPLETED);
        assertThat(row.learnerId()).isEqualTo(LEARNER);
        assertThat(row.payload())
                .containsEntry("marksAwarded", 2)
                .containsEntry("marksPossible", 3)
                .containsEntry("validationPassed", true)
                .containsEntry("authoritative", false)
                .containsEntry("modelId", "llama-3.3-70b-versatile")
                .containsEntry("pipelineVersion", "1.0.0");
    }

    @Test
    @DisplayName("human marks and overrides append HUMAN_MARK_RECORDED")
    void humanMarkRecorded() {
        UUID answer = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        service.onHumanMarkRecorded(new HumanMarkRecordedEvent(
                answer, ATTEMPT, LEARNER, QUESTION, 1, true, marker, WHEN));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.HUMAN_MARK_RECORDED);
        assertThat(row.payload())
                .containsEntry("marksAwarded", 1)
                .containsEntry("revising", true)
                .containsEntry("markerId", marker.toString());
    }

    // ── D2: the deterministic refusal provider persists (s145 rider) ────────

    @Test
    @DisplayName("D2: a tutor answer serializes answerProvider — the grounding-gate refusal is named")
    void tutorAnsweredPersistsTheRefusalProvider() {
        service.onTutorAnswered(new TutorAnsweredEvent(
                LEARNER, "what is chromatography?", List.of(NODE), 0, List.of(),
                true, null, "tutor-grounded/v5", 3.0, WHEN, null, 0, null,
                "deterministic-refusal"));

        verify(events).save(saved.capture());
        TelemetryEvent row = saved.getValue();
        assertThat(row.type()).isEqualTo(TelemetryEvent.Type.KA_RAG_COMPLETED);
        assertThat(row.payload())
                .containsEntry("refused", true)
                .containsEntry("answerProvider", "deterministic-refusal")
                .containsEntry("answerModel", "");
    }

    @Test
    @DisplayName("D2: the fail-open guard's refusal is distinguishable from the grounding gate")
    void tutorAnsweredPersistsThePaperRefusalProvider() {
        service.onTutorAnswered(new TutorAnsweredEvent(
                LEARNER, "explain question 10 from june 2019 paper 2", List.of(NODE), 0,
                List.of(), true, null, "tutor-grounded/v5", 3.0, WHEN, null, 0, null,
                "deterministic-paper-refusal"));

        verify(events).save(saved.capture());
        assertThat(saved.getValue().payload())
                .containsEntry("answerProvider", "deterministic-paper-refusal");
    }

    @Test
    @DisplayName("D2: a grounded answer carries the generator's provider name, never blank")
    void tutorAnsweredPersistsTheGeneratorProvider() {
        service.onTutorAnswered(new TutorAnsweredEvent(
                LEARNER, "bonding question", List.of(NODE), 4,
                List.of("MARK_SCHEME", "KNOWLEDGE_NODE"), false, "model-x",
                "tutor-grounded/v5", 1200.0, WHEN, "EXPLANATION", 0, null, "groq"));

        verify(events).save(saved.capture());
        assertThat(saved.getValue().payload())
                .containsEntry("answerProvider", "groq")
                .containsEntry("answerModel", "model-x");
    }

    // ── audit M1: telemetry must never fail the serving path ────────────────

    @Test
    @DisplayName("M1: a telemetry write failure never propagates out of the ask path — the row is dropped with a WARN")
    void tutorAnsweredSwallowsRepositoryFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("neon jitter: connection reset"))
                .when(events).save(org.mockito.ArgumentMatchers.any());

        org.assertj.core.api.Assertions.assertThatCode(() -> service.onTutorAnswered(
                        new TutorAnsweredEvent(LEARNER, "bonding question", List.of(NODE), 4,
                                List.of("MARK_SCHEME"), false, "model-x", "tutor-grounded/v5",
                                1200.0, WHEN, "EXPLANATION", 0, null, "groq")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("M1: the attempt path is equally insulated — a failed ATTEMPT_SUBMITTED write drops, never throws")
    void attemptEvidenceSwallowsRepositoryFailure() {
        org.mockito.Mockito.doThrow(new RuntimeException("neon jitter: connection reset"))
                .when(events).save(org.mockito.ArgumentMatchers.any());

        org.assertj.core.api.Assertions.assertThatCode(() -> service.onAssessmentEvidence(evidence(true)))
                .doesNotThrowAnyException();
    }
}

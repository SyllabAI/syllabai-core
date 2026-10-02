package com.syllabai.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.assessment.AttemptRepository;
import com.syllabai.learner.bdt.BdtEngine;
import com.syllabai.learner.bkt.BktEngine;
import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.MasteryUpdatedEvent;
import com.syllabai.shared.events.MisconceptionUpdatedEvent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The BDT evidence contract the audit found missing: a correct answer must weaken
 * the misconceptions an item monitors (updateOnCorrect), while a tagged-distractor
 * choice strengthens them. Also covers the Mastery/Misconception events that feed
 * BKT_UPDATED / BDT_UPDATED telemetry.
 */
class LearnerModelServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final UUID QUESTION = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
    private static final UUID SPEC_POINT_1 = UUID.randomUUID();
    private static final UUID SPEC_POINT_2 = UUID.randomUUID();
    private static final UUID MISCONCEPTION = UUID.randomUUID();
    private static final Instant WHEN = Instant.parse("2026-09-03T12:00:00Z");

    private final SkillStateRepository skillStates = mock(SkillStateRepository.class);
    private final MisconceptionStateRepository misconceptionStates =
            mock(MisconceptionStateRepository.class);
    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final LearnerModelService service = new LearnerModelService(
            skillStates, misconceptionStates, attempts, new BktEngine(), new BdtEngine(),
            new EbbinghausDecayService(),
            new LearnerProperties(null, null, null, null), published::add);

    private AssessmentEvidenceRecordedEvent evidence(boolean correct,
                                                     List<UUID> expressed,
                                                     List<UUID> observed) {
        return new AssessmentEvidenceRecordedEvent(
                ATTEMPT, LEARNER, QUESTION, List.of(NODE), List.of(SPEC_POINT_1, SPEC_POINT_2), correct, 1, correct ? 1 : 0,
                1000L, 3, false, false, expressed, observed, "test", null, 0, WHEN);
    }

    @Test
    @DisplayName("a correct answer weakens every misconception the item monitors (BDT update-on-correct)")
    void correctAnswerWeakensMonitoredMisconceptions() {
        // prior 0.75 (learner previously picked the tagged distractor twice)
        MisconceptionState state = new MisconceptionState(LEARNER, MISCONCEPTION, 0.75, WHEN);
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(LEARNER, MISCONCEPTION))
                .thenReturn(Optional.of(state));
        when(skillStates.findByLearnerIdAndNodeId(any(), any())).thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(true, List.of(), List.of(MISCONCEPTION)));

        // 0.75 * 0.3 / (0.75 * 0.3 + 0.25 * 0.9) = 0.5
        assertThat(state.probability()).isCloseTo(0.5, within(1e-9));
        verify(misconceptionStates).saveAll(List.of(state));
    }

    @Test
    @DisplayName("a tagged-distractor wrong answer strengthens the expressed misconception")
    void taggedWrongAnswerStrengthensMisconception() {
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(LEARNER, MISCONCEPTION))
                .thenReturn(Optional.empty());   // fresh state at the 0.3 prior
        when(skillStates.findByLearnerIdAndNodeId(any(), any())).thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(false, List.of(MISCONCEPTION), List.of(MISCONCEPTION)));

        // 0.3 * 0.7 / (0.3 * 0.7 + 0.7 * 0.1) = 0.75
        ArgumentCaptor<List<MisconceptionState>> saved = ArgumentCaptor.captor();
        verify(misconceptionStates).saveAll(saved.capture());
        assertThat(saved.getValue().get(0).probability()).isCloseTo(0.75, within(1e-9));
        assertThat(saved.getValue().get(0).evidenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a wrong answer on an untagged distractor is neutral — no misconception update")
    void untaggedWrongAnswerIsNeutral() {
        service.onAssessmentEvidence(evidence(false, List.of(), List.of(MISCONCEPTION)));

        verify(misconceptionStates, never()).saveAll(any());
        assertThat(published).noneMatch(e -> e instanceof MisconceptionUpdatedEvent);
    }

    @Test
    @DisplayName("a correct answer with no monitored misconceptions updates nothing")
    void correctAnswerWithoutMonitoredMisconceptionsIsNeutral() {
        service.onAssessmentEvidence(evidence(true, List.of(), List.of()));

        verify(misconceptionStates, never()).saveAll(any());
        assertThat(published).noneMatch(e -> e instanceof MisconceptionUpdatedEvent);
    }

    @Test
    @DisplayName("BKT posterior is applied and a MasteryUpdatedEvent is published for telemetry")
    void bktUpdatePublishesMasteryEvent() {
        SkillState state = new SkillState(LEARNER, NODE, 0.1131, WHEN);
        when(skillStates.findByLearnerIdAndNodeId(LEARNER, NODE))
                .thenReturn(Optional.of(state));
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(true, List.of(), List.of()));

        // posterior 0.3147 + (1 - 0.3147) * 0.1 = 0.3832
        assertThat(state.mastery()).isCloseTo(0.3832, within(1e-4));
        assertThat(state.attempts()).isEqualTo(1);
        assertThat(state.correctCount()).isEqualTo(1);

        MasteryUpdatedEvent event = published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent)
                .map(e -> (MasteryUpdatedEvent) e)
                .findFirst().orElseThrow();
        assertThat(event.nodeId()).isEqualTo(NODE);
        assertThat(event.priorMastery()).isCloseTo(0.1131, within(1e-9));
        assertThat(event.posteriorMastery()).isCloseTo(0.3832, within(1e-4));
        assertThat(event.correctness()).isTrue();
        // C1: at zero gap the computed forecast equals the anchor — the legacy
        // same-day behaviour is bit-preserved
        assertThat(event.decayedPrior()).isCloseTo(0.1131, within(1e-9));
        assertThat(event.gapDays()).isZero();
    }

    @Test
    @DisplayName("C1: the update prior is the DECAYED anchor — 180-day return at anchor 0.9 updates from 0.5496, not 0.9")
    void longGapUpdatesFromTheDecayedForecast() {
        // anchor 0.9 sits in the high band (>= 0.8) so τ = 365 days:
        // decayed = 0.9·e^(−180/365) = 0.5496293150633604 (hand-derived)
        SkillState state = new SkillState(LEARNER, NODE, 0.9,
                WHEN.minus(180, ChronoUnit.DAYS));
        when(skillStates.findByLearnerIdAndNodeId(LEARNER, NODE))
                .thenReturn(Optional.of(state));
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(true, List.of(), List.of()));

        MasteryUpdatedEvent event = published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent)
                .map(e -> (MasteryUpdatedEvent) e)
                .findFirst().orElseThrow();
        // the anchor itself is untouched in the telemetry (S1 forensics keep working)
        assertThat(event.priorMastery()).isCloseTo(0.9, within(1e-9));
        // the forecast the update consumed is the computed, never-persisted decay
        assertThat(event.decayedPrior()).isCloseTo(0.5496293150633604, within(1e-9));
        assertThat(event.gapDays()).isEqualTo(180L);
        // posterior: 0.5496·0.9/(0.5496·0.9 + 0.4504·0.25) = 0.8146, + T → 0.8331 —
        // NOT the legacy 0.9731, which erased the gap entirely
        assertThat(event.posteriorMastery())
                .isCloseTo(0.8331298589765962, within(1e-9));
        assertThat(state.mastery()).isCloseTo(0.8331298589765962, within(1e-9));
        assertThat(state.lastPracticedAt()).isEqualTo(WHEN);
    }

    @Test
    @DisplayName("C1: the decay floor keeps the update prior at l0 — a decade-gone 0.15 anchor updates from 0.1")
    void decayFloorKeepsPriorAtAboveL0() {
        // anchor 0.15 is low band (< 0.45) so τ = 30 days; 3650 days → curve ≈ 0,
        // the floor lifts the forecast to 0.1 (= l0: the learner has been exposed)
        SkillState state = new SkillState(LEARNER, NODE, 0.15,
                WHEN.minus(3650, ChronoUnit.DAYS));
        when(skillStates.findByLearnerIdAndNodeId(LEARNER, NODE))
                .thenReturn(Optional.of(state));
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(true, List.of(), List.of()));

        MasteryUpdatedEvent event = published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent)
                .map(e -> (MasteryUpdatedEvent) e)
                .findFirst().orElseThrow();
        assertThat(event.decayedPrior()).isCloseTo(0.1, within(1e-9));
        // correct from 0.1 with the paper path: 0.3571 (same as a fresh learner —
        // ten years of silence costs everything above l0)
        assertThat(event.posteriorMastery())
                .isCloseTo(0.3571428571428572, within(1e-9));
    }

    @Test
    @DisplayName("C1: clock skew (evidence older than the anchor) clamps to the anchor — never a gain")
    void futureTimestampClampsToTheAnchor() {
        SkillState state = new SkillState(LEARNER, NODE, 0.77,
                WHEN.plus(1, ChronoUnit.HOURS));
        when(skillStates.findByLearnerIdAndNodeId(LEARNER, NODE))
                .thenReturn(Optional.of(state));
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(true, List.of(), List.of()));

        MasteryUpdatedEvent event = published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent)
                .map(e -> (MasteryUpdatedEvent) e)
                .findFirst().orElseThrow();
        assertThat(event.decayedPrior()).isCloseTo(0.77, within(1e-9));
        assertThat(event.gapDays()).isZero();
    }

    @Test
    @DisplayName("the question's mapped spec points ride the same evidence class — skills fire on points, not just topics")
    void specPointsReceiveTheSameEvidence() {
        when(skillStates.findByLearnerIdAndNodeId(any(), any())).thenReturn(Optional.empty());
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(true, List.of(), List.of()));

        ArgumentCaptor<List<SkillState>> saved = ArgumentCaptor.captor();
        verify(skillStates).saveAll(saved.capture());
        // the topic node first (event order), then the mapped spec points —
        // every node gets the SAME marked-attempt update (T-C18 mapping finally
        // feeds the learner model: the hub's KG paints points, not just topics)
        assertThat(saved.getValue())
                .extracting(SkillState::nodeId)
                .containsExactly(NODE, SPEC_POINT_1, SPEC_POINT_2);
        for (SkillState s : saved.getValue()) {
            assertThat(s.attempts()).isEqualTo(1);
            assertThat(s.correctCount()).isEqualTo(1);
        }
        long masteryEvents = published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent).count();
        assertThat(masteryEvents).isEqualTo(3);   // one per evidence node
    }

    @Test
    @DisplayName("a spec point that IS the topic node is deduped — one update per node per attempt")
    void specPointDedupedAgainstTopicNodes() {
        when(skillStates.findByLearnerIdAndNodeId(any(), any())).thenReturn(Optional.empty());
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        AssessmentEvidenceRecordedEvent event = new AssessmentEvidenceRecordedEvent(
                ATTEMPT, LEARNER, QUESTION, List.of(NODE), List.of(NODE), true, 1, 1,
                1000L, 3, false, false, List.of(), List.of(), "test", null, 0, WHEN);
        service.onAssessmentEvidence(event);

        ArgumentCaptor<List<SkillState>> saved = ArgumentCaptor.captor();
        verify(skillStates).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).nodeId()).isEqualTo(NODE);
    }

    @Test
    @DisplayName("every BDT update publishes a MisconceptionUpdatedEvent with evidence kind")
    void bdtUpdatePublishesMisconceptionEvent() {
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(LEARNER, MISCONCEPTION))
                .thenReturn(Optional.empty());
        when(skillStates.findByLearnerIdAndNodeId(any(), any())).thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(false, List.of(MISCONCEPTION), List.of(MISCONCEPTION)));

        MisconceptionUpdatedEvent event = published.stream()
                .filter(e -> e instanceof MisconceptionUpdatedEvent)
                .map(e -> (MisconceptionUpdatedEvent) e)
                .findFirst().orElseThrow();
        assertThat(event.misconceptionNodeId()).isEqualTo(MISCONCEPTION);
        assertThat(event.expressed()).isTrue();
        assertThat(event.priorProbability()).isCloseTo(0.3, within(1e-9));
        assertThat(event.posteriorProbability()).isCloseTo(0.75, within(1e-9));
    }

    // ── S2/ADR-033: format-aware emission pins ─────────────────────────────────
    // First-attempt (gap-0) updates whose constants are hand-derived from the
    // BktEngine update formula; the typedEvidence helper emits a single-node
    // event with an explicit format so the resolver, not the paper constant,
    // decides the guess.

    private AssessmentEvidenceRecordedEvent typedEvidence(boolean correct,
                                                          String questionType,
                                                          int optionCount) {
        return new AssessmentEvidenceRecordedEvent(
                ATTEMPT, LEARNER, QUESTION, List.of(NODE), List.of(), correct, 1, correct ? 1 : 0,
                1000L, 3, false, false, List.of(), List.of(), "test", questionType, optionCount, WHEN);
    }

    private LearnerModelService serviceOverState(double seedMastery) {
        SkillState state = new SkillState(LEARNER, NODE, seedMastery, WHEN);
        when(skillStates.findByLearnerIdAndNodeId(LEARNER, NODE)).thenReturn(Optional.of(state));
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());
        return new LearnerModelService(skillStates, misconceptionStates, attempts,
                new BktEngine(), new BdtEngine(), new EbbinghausDecayService(),
                new LearnerProperties(null, null, null, null), published::add);
    }

    @Test
    @DisplayName("structured-correct is no longer under-credited: l0 0.1 reads 0.9182, not 0.3571 (2.57x)")
    void structuredCorrectIsFullyCredited() {
        serviceOverState(0.1).onAssessmentEvidence(typedEvidence(true, "STRUCTURED", 0));

        assertThat(published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent)
                .map(e -> (MasteryUpdatedEvent) e)
                .findFirst().orElseThrow().posteriorMastery())
                .isCloseTo(0.9181818181818182, within(1e-9));
    }

    @Test
    @DisplayName("five-option MCQ: correct from l0 0.1 reads exactly 0.4 (guess 1/5)")
    void fiveOptionMcqReadsExactlyFortyPercent() {
        serviceOverState(0.1).onAssessmentEvidence(typedEvidence(true, "MCQ_SINGLE", 5));

        assertThat(published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent)
                .map(e -> (MasteryUpdatedEvent) e)
                .findFirst().orElseThrow().posteriorMastery())
                .isCloseTo(0.4, within(1e-12));
    }

    @Test
    @DisplayName("a wrong structured answer is no longer over-forgiven: prior 0.5 drops to 0.1826, not 0.2059")
    void wrongStructuredIsNotOverForgiven() {
        serviceOverState(0.5).onAssessmentEvidence(typedEvidence(false, "STRUCTURED", 0));

        assertThat(published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent)
                .map(e -> (MasteryUpdatedEvent) e)
                .findFirst().orElseThrow().posteriorMastery())
                .isCloseTo(0.18256880733944955, within(1e-9));
    }

    @Test
    @DisplayName("untyped wrong answers keep the exact legacy over-forgiveness (0.2059) — strict refinement")
    void untypedWrongKeepsLegacyPath() {
        serviceOverState(0.5).onAssessmentEvidence(typedEvidence(false, null, 0));

        assertThat(published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent)
                .map(e -> (MasteryUpdatedEvent) e)
                .findFirst().orElseThrow().posteriorMastery())
                .isCloseTo(0.2058823529411765, within(1e-9));
    }

    @Test
    @DisplayName("C3 at the behaviour level: a malformed 1-option MCQ degrades to the paper path, never inverts")
    void malformedMcqCountDegradesInsteadOfInverting() {
        // guess would be 1/1 = 1.0 unguarded: the wrong-path posterior becomes
        // p·slip/(p·slip + (1−p)·0) = 1.0 — a WRONG answer driving mastery to 1.
        serviceOverState(0.5).onAssessmentEvidence(typedEvidence(false, "MCQ_SINGLE", 1));

        assertThat(published.stream()
                .filter(e -> e instanceof MasteryUpdatedEvent)
                .map(e -> (MasteryUpdatedEvent) e)
                .findFirst().orElseThrow().posteriorMastery())
                .isCloseTo(0.2058823529411765, within(1e-9));   // the paper-default wrong path
    }
}

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
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.MasteryUpdatedEvent;
import com.syllabai.shared.events.MisconceptionUpdatedEvent;
import java.time.Instant;
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
 * BKT_UPDATED / BDT_UPDATED telemetry, and the S2/ADR-033 format-aware emission
 * resolution (the guess base is priced per question format).
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
            new LearnerProperties(null, null, null, null), published::add);

    private AssessmentEvidenceRecordedEvent evidence(boolean correct,
                                                     List<UUID> expressed,
                                                     List<UUID> observed) {
        // legacy shape: no format on the event — the paper-default emission path
        return evidence(correct, null, 0, expressed, observed);
    }

    private AssessmentEvidenceRecordedEvent evidence(boolean correct, String questionType,
                                                     int optionCount,
                                                     List<UUID> expressed,
                                                     List<UUID> observed) {
        return new AssessmentEvidenceRecordedEvent(
                ATTEMPT, LEARNER, QUESTION, questionType, optionCount,
                List.of(NODE), List.of(SPEC_POINT_1, SPEC_POINT_2), correct, 1, correct ? 1 : 0,
                1000L, 3, false, false, expressed, observed, "test", WHEN);
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
                ATTEMPT, LEARNER, QUESTION, null, 0, List.of(NODE), List.of(NODE), true, 1, 1,
                1000L, 3, false, false, List.of(), List.of(), "test", WHEN);
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

    @Test
    @DisplayName("S2/ADR-033: a correct STRUCTURED answer is near-conclusive — not priced as a 1-in-4 guess")
    void structuredCorrectAnswerIsCreditedAsNearConclusive() {
        when(skillStates.findByLearnerIdAndNodeId(any(), any())).thenReturn(Optional.empty());
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(true, "STRUCTURED", 0, List.of(), List.of()));

        ArgumentCaptor<List<SkillState>> saved = ArgumentCaptor.captor();
        verify(skillStates).saveAll(saved.capture());
        // posterior = 0.1*0.9 / (0.1*0.9 + 0.9*0.01) = 0.909091; + T on the remainder
        // The format-blind model priced the SAME performance at 0.357 — a 2.6x under-credit.
        assertThat(saved.getValue().get(0).mastery()).isCloseTo(0.9181818, within(1e-6));
    }

    @Test
    @DisplayName("S2/ADR-033: the MCQ guess base is 1/optionCount — the paper's 0.25 is exactly the four-option value")
    void mcqGuessBaseIsPerOptionCount() {
        when(skillStates.findByLearnerIdAndNodeId(any(), any())).thenReturn(Optional.empty());
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(true, "MCQ_SINGLE", 5, List.of(), List.of()));

        ArgumentCaptor<List<SkillState>> saved = ArgumentCaptor.captor();
        verify(skillStates).saveAll(saved.capture());
        // posterior = 0.1*0.9 / (0.1*0.9 + 0.9*0.2) = 1/3; + (1 - 1/3)*0.1 = 0.4 exactly
        assertThat(saved.getValue().get(0).mastery()).isCloseTo(0.4, within(1e-9));
    }

    @Test
    @DisplayName("S2/ADR-033: MCQ_SINGLE with four options is identical to the paper-default path")
    void fourOptionMcqMatchesThePaperDefault() {
        SkillState state = new SkillState(LEARNER, NODE, 0.1131, WHEN);
        when(skillStates.findByLearnerIdAndNodeId(LEARNER, NODE)).thenReturn(Optional.of(state));
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(true, "MCQ_SINGLE", 4, List.of(), List.of()));

        // same numbers as the untyped legacy pin above — 1/4 == 0.25 == paper default
        assertThat(state.mastery()).isCloseTo(0.3832, within(1e-4));
    }

    @Test
    @DisplayName("S2/ADR-033: a wrong STRUCTURED answer is no longer forgiven as a lucky guess")
    void structuredWrongAnswerIsNotOverForgiven() {
        SkillState state = new SkillState(LEARNER, NODE, 0.5, WHEN);
        when(skillStates.findByLearnerIdAndNodeId(LEARNER, NODE)).thenReturn(Optional.of(state));
        when(misconceptionStates.findByLearnerIdAndMisconceptionNodeId(any(), any()))
                .thenReturn(Optional.empty());

        service.onAssessmentEvidence(evidence(false, "STRUCTURED", 0, List.of(), List.of()));

        // posterior = 0.5*0.1 / (0.5*0.1 + 0.5*0.99) = 0.0917431; + T on the remainder
        // (the format-blind model forgave it to 0.2062 — a wrong worked answer treated
        // as if a quarter of non-knowers produce one)
        assertThat(state.mastery()).isCloseTo(0.1825688, within(1e-6));
    }
}

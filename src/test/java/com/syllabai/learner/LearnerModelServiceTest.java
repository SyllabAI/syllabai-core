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
 * BKT_UPDATED / BDT_UPDATED telemetry.
 */
class LearnerModelServiceTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();
    private static final UUID QUESTION = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();
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
        return new AssessmentEvidenceRecordedEvent(
                ATTEMPT, LEARNER, QUESTION, List.of(NODE), correct, 1, correct ? 1 : 0,
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
}

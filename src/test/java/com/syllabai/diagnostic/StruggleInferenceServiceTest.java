package com.syllabai.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.knowledge.KnowledgeGraphService;
import com.syllabai.knowledge.dto.PrerequisiteView;
import com.syllabai.learner.LearnerModelService;
import com.syllabai.learner.SkillState;
import com.syllabai.shared.events.AssessmentEvidenceRecordedEvent;
import com.syllabai.shared.events.StruggleInferredEvent;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * T-027 rule engine: every rule, threshold boundaries, absence of evidence,
 * multiple simultaneous signals, expiry, superseding, and the guarantee that
 * unsupported struggle categories are never emitted.
 */
class StruggleInferenceServiceTest {

    private final StruggleInferenceRepository inferences = mock(StruggleInferenceRepository.class);
    private final LearnerModelService learnerModel = mock(LearnerModelService.class);
    private final KnowledgeGraphService knowledgeGraph = mock(KnowledgeGraphService.class);
    private final List<Object> publishedEvents = new ArrayList<>();
    private final ApplicationEventPublisher events = publishedEvents::add;

    private final StruggleInferenceService service =
            new StruggleInferenceService(inferences, learnerModel, knowledgeGraph, events);

    private final UUID learnerId = UUID.randomUUID();
    private final UUID topicId = UUID.randomUUID();
    private final UUID prereqId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-09-04T12:00:00Z");

    @SuppressWarnings("unchecked")
    private <T> List<T> savedInferences() {
        ArgumentCaptor<StruggleInference> captor = ArgumentCaptor.forClass(StruggleInference.class);
        verify(inferences, atLeast(0)).save(captor.capture());
        return (List<T>) (List<?>) captor.getAllValues().stream()
                .filter(i -> i.supersededAt() == null)   // fresh inserts only, not supersede updates
                .toList();
    }

    private AssessmentEvidenceRecordedEvent event(boolean correctness, boolean selfDoubt, boolean timed) {
        return new AssessmentEvidenceRecordedEvent(
                UUID.randomUUID(), learnerId, UUID.randomUUID(), List.of(topicId),
                correctness, 1, correctness ? 1 : 0, 30_000L, 3, selfDoubt, timed,
                List.of(), List.of(), "unit-test", now);
    }

    private SkillState skill(UUID nodeId, double mastery, Double fluencyGap) {
        try {
            SkillState state = new SkillState(learnerId, nodeId, 0.1, now);
            Field masteryField = SkillState.class.getDeclaredField("mastery");
            masteryField.setAccessible(true);
            masteryField.set(state, mastery);
            if (fluencyGap != null) {
                Field gapField = SkillState.class.getDeclaredField("proceduralFluencyGap");
                gapField.setAccessible(true);
                gapField.set(state, fluencyGap);
            }
            return state;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void prerequisitesFor(PrerequisiteView... views) {
        when(knowledgeGraph.prerequisiteChain(topicId)).thenReturn(List.of(views));
    }

    private static PrerequisiteView prereq(UUID id, String code) {
        return new PrerequisiteView(id, code, "TOPIC", "prereq " + code, 1);
    }

    // ---------------------------------------------------------- prerequisite rule

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("weak prerequisite below 0.50 infers PREREQUISITE_GAP with evidence")
    void prerequisiteGapInfers() {
        prerequisitesFor(prereq(prereqId, "IALCHEM2018-U1-T1"));
        when(learnerModel.skillStates(learnerId))
                .thenReturn(List.of(skill(prereqId, 0.20, null)));

        service.onAssessmentEvidence(event(false, false, false));

        var saved = savedInferences();
        assertThat(saved).hasSize(1);
        StruggleInference inference = (StruggleInference) saved.get(0);
        assertThat(inference.type()).isEqualTo(StruggleType.PREREQUISITE_GAP);
        assertThat(inference.subtype()).isEqualTo("1a_weak_prerequisite_mastery");
        assertThat(inference.probability()).isBetween(0.55, 0.95);
        assertThat(inference.supportingEvidence())
                .containsEntry("signal", "weak_prerequisite_mastery")
                .containsEntry("weakest_mastery", 0.20)
                .containsEntry("prerequisite_count", 1)
                .containsEntry("prerequisite_codes", List.of("IALCHEM2018-U1-T1"));
        assertThat(inference.modelVersion()).isEqualTo("rules-v0.2");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("boundary: prerequisite mastery exactly 0.50 is NOT weak — no inference")
    void prerequisiteBoundaryAtThreshold() {
        prerequisitesFor(prereq(prereqId, "IALCHEM2018-U1-T1"));
        when(learnerModel.skillStates(learnerId))
                .thenReturn(List.of(skill(prereqId, 0.50, null)));

        service.onAssessmentEvidence(event(false, false, false));

        assertThat(savedInferences()).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("boundary: prerequisite mastery 0.499 IS weak — inference fires")
    void prerequisiteBoundaryJustBelow() {
        prerequisitesFor(prereq(prereqId, "IALCHEM2018-U1-T1"));
        when(learnerModel.skillStates(learnerId))
                .thenReturn(List.of(skill(prereqId, 0.499, null)));

        service.onAssessmentEvidence(event(false, false, false));

        assertThat(savedInferences()).hasSize(1);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("absence of evidence: no skill state for prerequisites — no inference, no guess")
    void prerequisiteNoState() {
        prerequisitesFor(prereq(prereqId, "IALCHEM2018-U1-T1"));
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of());

        service.onAssessmentEvidence(event(false, false, false));

        assertThat(savedInferences()).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("no prerequisite chain on the topic — no prerequisite inference")
    void noPrerequisiteChain() {
        prerequisitesFor();
        when(learnerModel.skillStates(learnerId))
                .thenReturn(List.of(skill(topicId, 0.4, null)));

        service.onAssessmentEvidence(event(false, false, false));

        assertThat(savedInferences()).isEmpty();
    }

    // ---------------------------------------------------------- fluency rule

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("fluency gap above 0.15 infers EXAM_LITERACY 3b with the gap as evidence")
    void fluencyGapInfers() {
        prerequisitesFor();
        when(learnerModel.skillStates(learnerId))
                .thenReturn(List.of(skill(topicId, 0.80, 0.30)));

        service.onAssessmentEvidence(event(false, false, true));

        var saved = savedInferences();
        assertThat(saved).hasSize(1);
        StruggleInference inference = (StruggleInference) saved.get(0);
        assertThat(inference.type()).isEqualTo(StruggleType.EXAM_LITERACY);
        assertThat(inference.subtype()).isEqualTo("3b_procedural_fluency");
        assertThat(inference.probability()).isBetween(0.50, 0.95);
        assertThat(inference.supportingEvidence()).containsEntry("gap", 0.30);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("boundary: fluency gap exactly 0.15 fires; 0.149 does not")
    void fluencyBoundary() {
        prerequisitesFor();
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of(skill(topicId, 0.80, 0.15)));
        service.onAssessmentEvidence(event(false, false, true));
        assertThat(savedInferences()).hasSize(1);

        resetCounts();
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of(skill(topicId, 0.80, 0.149)));
        service.onAssessmentEvidence(event(false, false, true));
        assertThat(savedInferences()).isEmpty();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("null fluency gap (missing timed or untimed condition) — no inference")
    void fluencyNullGapNoInference() {
        prerequisitesFor();
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of(skill(topicId, 0.80, null)));

        service.onAssessmentEvidence(event(false, false, true));

        assertThat(savedInferences()).isEmpty();
    }

    // ---------------------------------------------------------- metacognitive rule

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("self-doubt flag infers METACOGNITIVE 5a; no flag does not")
    void selfDoubtInfers() {
        prerequisitesFor();
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of());

        service.onAssessmentEvidence(event(true, true, false));
        assertThat(savedInferences()).hasSize(1);
        StruggleInference inference = (StruggleInference) savedInferences().get(0);
        assertThat(inference.type()).isEqualTo(StruggleType.METACOGNITIVE);
        assertThat(inference.subtype()).isEqualTo("5a_self_efficacy");
        assertThat(inference.supportingEvidence()).containsEntry("signal", "self_doubt_flag");

        resetCounts();
        service.onAssessmentEvidence(event(true, false, false));
        assertThat(savedInferences()).isEmpty();
    }

    // ---------------------------------------------------------- combined signals

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("multiple simultaneous signals emit distinct typed inferences")
    void multipleSignals() {
        prerequisitesFor(prereq(prereqId, "IALCHEM2018-U1-T1"));
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of(
                skill(prereqId, 0.10, null),
                skill(topicId, 0.80, 0.40)));

        service.onAssessmentEvidence(event(false, true, true));

        var saved = savedInferences();
        assertThat(saved).hasSize(3);
        assertThat(saved.stream().map(i -> ((StruggleInference) i).type()))
                .containsExactlyInAnyOrder(
                        StruggleType.PREREQUISITE_GAP, StruggleType.EXAM_LITERACY, StruggleType.METACOGNITIVE);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("unsupported struggle types are never emitted, even on total evidence")
    void unsupportedNeverEmitted() {
        prerequisitesFor(prereq(prereqId, "IALCHEM2018-U1-T1"));
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of(
                skill(prereqId, 0.0, null),
                skill(topicId, 0.0, 1.0)));

        service.onAssessmentEvidence(event(false, true, true));

        var saved = savedInferences();
        assertThat(saved).isNotEmpty();
        // the conservative v0 ruleset only ever emits these three:
        assertThat(saved.stream().map(i -> ((StruggleInference) i).type()))
                .containsOnly(StruggleType.PREREQUISITE_GAP, StruggleType.EXAM_LITERACY, StruggleType.METACOGNITIVE);
    }

    // ---------------------------------------------------------- expiry + supersede

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("every inference expires exactly 7 days after generation")
    void expiry() {
        prerequisitesFor();
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of());

        service.onAssessmentEvidence(event(true, true, false));

        StruggleInference inference = (StruggleInference) savedInferences().get(0);
        assertThat(inference.generatedAt()).isEqualTo(now);
        assertThat(inference.expiresAt()).isEqualTo(now.plus(7, ChronoUnit.DAYS));
        assertThat(inference.expiresAt()).isAfter(inference.generatedAt());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a later assessment supersedes the previous same-type inference without deleting it")
    void supersedeKeepsHistory() {
        prerequisitesFor();
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of());
        StruggleInference stale = new StruggleInference(learnerId, topicId,
                StruggleType.METACOGNITIVE, "5a_self_efficacy", 0.80,
                Map.of("signal", "self_doubt_flag"), "rules-v0.2",
                now.minus(1, ChronoUnit.DAYS), now.plus(6, ChronoUnit.DAYS));
        when(inferences.findByLearnerIdAndTopicNodeIdAndTypeAndExpiresAtAfterAndSupersededAtIsNull(
                eq(learnerId), eq(topicId), eq(StruggleType.METACOGNITIVE), any()))
                .thenReturn(List.of(stale));

        service.onAssessmentEvidence(event(true, true, false));

        // old row: kept, marked superseded, re-persisted
        assertThat(stale.supersededAt()).isNotNull();
        verify(inferences).save(stale);
        // new row: inserted (the supersede update + the insert both go through save)
        var inserted = savedInferences();
        assertThat(inserted).hasSize(1);
        StruggleInference fresh = (StruggleInference) inserted.get(0);
        assertThat(fresh.supersededAt()).isNull();
        assertThat(fresh.generatedAt()).isEqualTo(now);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("re-assessment without the signal does not resurrect any inference")
    void noResurrectionWithoutSignal() {
        prerequisitesFor();
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of());

        service.onAssessmentEvidence(event(false, false, false));

        assertThat(savedInferences()).isEmpty();
        verify(inferences, never()).save(any(StruggleInference.class));
    }

    // ---------------------------------------------------------- events

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("every save publishes a research-visible StruggleInferredEvent")
    void publishesEvent() {
        prerequisitesFor();
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of());

        service.onAssessmentEvidence(event(true, true, false));

        assertThat(publishedEvents).hasSize(1);
        StruggleInferredEvent event = (StruggleInferredEvent) publishedEvents.get(0);
        assertThat(event.learnerId()).isEqualTo(learnerId);
        assertThat(event.type()).isEqualTo(StruggleType.METACOGNITIVE);
        assertThat(event.modelVersion()).isEqualTo("rules-v0.2");
        assertThat(event.probability()).isEqualTo(0.80);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("learner state is fetched once per event, not per rule")
    void singleModelFetch() {
        prerequisitesFor(prereq(prereqId, "IALCHEM2018-U1-T1"));
        when(learnerModel.skillStates(learnerId)).thenReturn(List.of(
                skill(prereqId, 0.1, null), skill(topicId, 0.8, 0.4)));

        service.onAssessmentEvidence(event(false, true, true));

        verify(learnerModel, times(1)).skillStates(learnerId);
    }

    private void resetCounts() {
        publishedEvents.clear();
        org.mockito.Mockito.clearInvocations(inferences);
    }
}

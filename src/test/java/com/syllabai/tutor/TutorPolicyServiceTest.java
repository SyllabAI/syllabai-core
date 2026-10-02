package com.syllabai.tutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.diagnostic.StruggleInference;
import com.syllabai.diagnostic.StruggleInferenceRepository;
import com.syllabai.diagnostic.StruggleType;
import com.syllabai.learner.MisconceptionReading;
import com.syllabai.learner.MisconceptionState;
import com.syllabai.shared.events.TutorInterventionSelectedEvent;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentMatcher;
import org.mockito.ArgumentMatchers;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext.MatchedTopic;
import com.syllabai.tutor.KnowledgeRetriever.KnowledgeContext.MisconceptionSignal;

/**
 * T-026 intervention policy: documented precedence order, threshold
 * boundaries, teacher overrides, expiry/supersede read contract,
 * determinism, and anonymous vs learner-aware requests.
 */
class TutorPolicyServiceTest {

    private final StruggleInferenceRepository inferences = mock(StruggleInferenceRepository.class);
    private final List<Object> published = new ArrayList<>();

    private final TutorPolicyService policy = new TutorPolicyService(
            inferences, published::add);

    private final UUID learnerId = UUID.randomUUID();
    private final UUID topicId = UUID.randomUUID();
    private final UUID misconceptionId = UUID.randomUUID();

    // M3 tranche 2: the readings are propagated by the CALLER (was: the policy
    // read them itself via LearnerModelService — a dependency it no longer has,
    // pinned at compile level); tests set this field where the old stub stood
    private List<MisconceptionReading> readings = List.of();

    private final List<MatchedTopic> topics =
            List.of(new MatchedTopic(topicId, "IALCHEM2018-U1-T3", "Bonding and Structure", 0.5));
    private final List<MisconceptionSignal> signals =
            List.of(new MisconceptionSignal(topicId, misconceptionId, "Moles and grams are interchangeable"));

    // -- helpers -------------------------------------------------------------

    private StruggleInference inference(StruggleType type, double probability, Instant generatedAt) {
        return new StruggleInference(learnerId, topicId, type, "subtype", probability,
                Map.of("signal", "test"), "rules-v0.2", generatedAt, generatedAt.plus(7, ChronoUnit.DAYS));
    }

    private void activeInferences(StruggleInference... list) {
        when(inferences.findByLearnerIdAndExpiresAtAfterAndSupersededAtIsNullOrderByProbabilityDescGeneratedAtDesc(
                any(), any())).thenReturn(List.of(list));
    }

    private MisconceptionState misconception(double probability) {
        try {
            MisconceptionState state = new MisconceptionState(learnerId, misconceptionId, 0.3, Instant.now());
            Field field = MisconceptionState.class.getDeclaredField("probability");
            field.setAccessible(true);
            field.set(state, probability);
            return state;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void override(StruggleInference inference, String decision) {
        inference.applyTeacherOverride(decision, UUID.randomUUID(), Instant.now());
    }

    /** ADR-032 stub: fresh evidence — the relaxed value equals the anchored posterior */
    private MisconceptionReading reading(MisconceptionState state) {
        return new MisconceptionReading(state, state.probability());
    }

    private TutorPolicyService.InterventionPlan select() {
        return policy.select(learnerId, topics, signals, readings);
    }

    // -- anonymous -----------------------------------------------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("anonymous request: explanation only, no repository read (no learner-model dependency at all)")
    void anonymousRequest() {
        TutorPolicyService.InterventionPlan plan = policy.select(null, topics, signals, List.of());

        assertThat(plan.type()).isEqualTo(TutorPolicyService.InterventionType.EXPLANATION);
        assertThat(plan.rationale()).isEqualTo("anonymous request");
        verify(inferences, times(0)).findByLearnerIdAndExpiresAtAfterAndSupersededAtIsNullOrderByProbabilityDescGeneratedAtDesc(any(), any());
    }

    // -- precedence 1: inference threshold ----------------------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("inference at 0.65 (boundary) drives the intervention")
    void thresholdBoundaryFires() {
        activeInferences(inference(StruggleType.PREREQUISITE_GAP, 0.65, Instant.now()));
        readings = List.of();

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.PREREQUISITE_REVIEW);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("inference at 0.649 (below boundary) does not drive the intervention")
    void thresholdBoundaryDoesNotFire() {
        activeInferences(inference(StruggleType.PREREQUISITE_GAP, 0.649, Instant.now()));
        readings = List.of();

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.EXPLANATION);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("type mapping: PREREQUISITE_GAP / EXAM_LITERACY / METACOGNITIVE interventions")
    void typeMapping() {
        readings = List.of();
        activeInferences(inference(StruggleType.PREREQUISITE_GAP, 0.9, Instant.now()));
        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.PREREQUISITE_REVIEW);

        activeInferences(inference(StruggleType.EXAM_LITERACY, 0.9, Instant.now()));
        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.PROCEDURAL_FLUENCY);

        activeInferences(inference(StruggleType.METACOGNITIVE, 0.9, Instant.now()));
        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.METACOGNITIVE_CHECK);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("strongest inference wins: 0.9 beats 0.7 regardless of order")
    void strongestWins() {
        readings = List.of();
        // repository orders probability DESC; policy takes the first qualifying row
        activeInferences(
                inference(StruggleType.EXAM_LITERACY, 0.9, Instant.now()),
                inference(StruggleType.PREREQUISITE_GAP, 0.7, Instant.now()));

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.PROCEDURAL_FLUENCY);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("inference on an unrelated topic is ignored (topic filter)")
    void unrelatedTopicIgnored() {
        StruggleInference elsewhere = new StruggleInference(learnerId, UUID.randomUUID(),
                StruggleType.PREREQUISITE_GAP, "subtype", 0.95, Map.of(), "rules-v0.2",
                Instant.now(), Instant.now().plus(7, ChronoUnit.DAYS));
        activeInferences(elsewhere);
        readings = List.of();

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.EXPLANATION);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("unsupported struggle type (MOTIVATIONAL 0.9) falls back to explanation, never a fabricated intervention")
    void unsupportedTypeFallsBack() {
        activeInferences(inference(StruggleType.MOTIVATIONAL, 0.9, Instant.now()));
        readings = List.of();

        TutorPolicyService.InterventionPlan plan = select();
        assertThat(plan.type()).isEqualTo(TutorPolicyService.InterventionType.EXPLANATION);
        assertThat(plan.rationale()).contains("unsupported struggle category");
    }

    // -- precedence 2: misconception vs generic explanation ------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("active misconception overrides generic low-mastery explanation")
    void misconceptionOverridesExplanation() {
        activeInferences();   // no qualifying inference
        readings = List.of(reading(misconception(0.50)));

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.MISCONCEPTION_REMEDIATION);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("boundary: misconception at 0.49 is not active — generic explanation")
    void misconceptionBoundary() {
        activeInferences();
        readings = List.of(reading(misconception(0.49)));

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.EXPLANATION);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("ADR-032: a year-old 0.9 diagnosis relaxes below the threshold — no remediation")
    void staleMisconceptionNoLongerTriggersRemediation() {
        activeInferences();   // no qualifying inference
        MisconceptionState stale;
        try {
            stale = new MisconceptionState(learnerId, misconceptionId, 0.3,
                    Instant.now().minus(java.time.Duration.ofDays(365)));
            Field field = MisconceptionState.class.getDeclaredField("probability");
            field.setAccessible(true);
            field.set(stale, 0.90);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        // the funnel computes the relaxed value from the anchor — same formula here
        MisconceptionReading staleReading = new MisconceptionReading(stale,
                new com.syllabai.learner.bdt.BdtEngine().relaxedToPrior(
                        stale.probability(), 0.3, stale.lastEvidenceAt(),
                        Instant.now(), java.time.Duration.ofDays(180)));
        org.assertj.core.api.Assumptions.assumeThat(staleReading.effective())
                .as("precondition: 365d staleness must relax 0.9 below the 0.5 threshold")
                .isLessThan(0.5);
        readings = List.of(staleReading);

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.EXPLANATION);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("misconception not attached to a matched topic is not active")
    void misconceptionUnattachedIgnored() {
        activeInferences();
        MisconceptionState elsewhere;
        try {
            elsewhere = new MisconceptionState(learnerId, UUID.randomUUID(), 0.3, Instant.now());
            Field field = MisconceptionState.class.getDeclaredField("probability");
            field.setAccessible(true);
            field.set(elsewhere, 0.90);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        readings = List.of(reading(elsewhere));

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.EXPLANATION);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("precedence: qualifying inference (0.9) beats active misconception")
    void inferenceBeatsMisconception() {
        activeInferences(inference(StruggleType.PREREQUISITE_GAP, 0.9, Instant.now()));
        readings = List.of(reading(misconception(0.9)));

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.PREREQUISITE_REVIEW);
    }

    // -- teacher overrides -----------------------------------------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("teacher-CONFIRMED inference drives the intervention even below threshold")
    void confirmedOverrideDrives() {
        StruggleInference weak = inference(StruggleType.PREREQUISITE_GAP, 0.30, Instant.now());
        override(weak, "confirmed");
        activeInferences(weak);
        readings = List.of();

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.PREREQUISITE_REVIEW);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("teacher-REJECTED inference is excluded from policy reads")
    void rejectedOverrideExcluded() {
        StruggleInference strong = inference(StruggleType.PREREQUISITE_GAP, 0.95, Instant.now());
        override(strong, "rejected");
        activeInferences(strong);
        readings = List.of();

        assertThat(select().type()).isEqualTo(TutorPolicyService.InterventionType.EXPLANATION);
    }

    // -- read contract: expiry + supersede ------------------------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("read path asks only for un-expired, un-superseded inferences (expiry enforced on reads)")
    void readContractEnforcedOnReads() {
        activeInferences();
        readings = List.of();

        select();

        // the repository call itself carries the expiry + supersede predicates
        verify(inferences).findByLearnerIdAndExpiresAtAfterAndSupersededAtIsNullOrderByProbabilityDescGeneratedAtDesc(
                ArgumentMatchers.eq(learnerId), ArgumentMatchers.argThat((ArgumentMatcher<Instant>) t -> !t.isAfter(Instant.now().plusSeconds(1))));
    }

    // -- determinism + telemetry ------------------------------------------------

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("deterministic: identical inputs produce the identical plan (repeated calls)")
    void deterministicSelection() {
        activeInferences(inference(StruggleType.EXAM_LITERACY, 0.8, Instant.parse("2026-09-01T00:00:00Z")));
        readings = List.of();

        TutorPolicyService.InterventionPlan first = select();
        TutorPolicyService.InterventionPlan second = select();

        assertThat(first.type()).isEqualTo(second.type());
        assertThat(first.rationale()).isEqualTo(second.rationale());
        assertThat(first.actions()).isEqualTo(second.actions());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("every selection publishes a versioned TutorInterventionSelectedEvent")
    void telemetryEmitted() {
        activeInferences();
        readings = List.of();

        select();

        assertThat(published).hasSize(1);
        TutorInterventionSelectedEvent event = (TutorInterventionSelectedEvent) published.get(0);
        assertThat(event.learnerId()).isEqualTo(learnerId);
        assertThat(event.interventionType()).isEqualTo("EXPLANATION");
        assertThat(event.policyVersion()).isEqualTo("rules-v0.2");
        assertThat(event.topicNodeIds()).containsExactly(topicId);
    }
}

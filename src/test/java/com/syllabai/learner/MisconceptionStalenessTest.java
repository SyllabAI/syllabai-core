package com.syllabai.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.learner.bdt.BdtEngine;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * MED-2 regression (ADR-032): stored BDT posteriors are evidence anchors. The
 * read funnel relaxes them toward the population prior by evidence age, ranks
 * by the relaxed value (a fresh 0.6 outranks a stale 0.9) and never writes.
 * Under the pre-fix behaviour — raw probability, raw ordering — every assertion
 * here that distinguishes stale from fresh would fail.
 */
class MisconceptionStalenessTest {

    private final MisconceptionStateRepository misconceptionStates =
            mock(MisconceptionStateRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final LearnerModelService service = new LearnerModelService(
            mock(SkillStateRepository.class), misconceptionStates, null, null, new BdtEngine(),
            new LearnerProperties(null, null, null, null), events);

    private static final double PRIOR = 0.3;

    private MisconceptionState row(double probability, Instant evidenceAt) {
        MisconceptionState state = new MisconceptionState(UUID.randomUUID(), UUID.randomUUID(),
                PRIOR, evidenceAt);
        try {
            Field field = MisconceptionState.class.getDeclaredField("probability");
            field.setAccessible(true);
            field.set(state, probability);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return state;
    }

    @Test
    @DisplayName("funnel ranks by relaxed value: fresh 0.6 outranks stale 0.9, and never persists")
    void freshOutranksStaleAndNothingIsWritten() {
        UUID learnerId = UUID.randomUUID();
        Instant now = Instant.now();
        MisconceptionState stale = row(0.9, now.minus(Duration.ofDays(200)));
        MisconceptionState fresh = row(0.6, now);

        // repository orders by RAW probability desc — stale first
        when(misconceptionStates.findByLearnerIdOrderByProbabilityDesc(learnerId))
                .thenReturn(List.of(stale, fresh));

        List<MisconceptionReading> readings = service.misconceptionReadings(learnerId);

        assertThat(readings).hasSize(2);
        assertThat(readings.get(0).state()).isSameAs(fresh);
        assertThat(readings.get(1).state()).isSameAs(stale);

        // fresh evidence: full posterior survives
        assertThat(readings.get(0).effective()).isCloseTo(0.6,
                org.assertj.core.data.Offset.offset(1e-9));
        // stale evidence: dev 0.6 relaxed 200d at tau 180d
        double expectedStale = PRIOR + 0.6 * Math.exp(-(double) Duration.ofDays(200).toNanos()
                / (double) Duration.ofDays(180).toNanos());
        // 1e-6 absorbs Instant.now() capture drift between test and service (a few hundred ns of extra age)
        assertThat(readings.get(1).effective()).isCloseTo(expectedStale,
                org.assertj.core.data.Offset.offset(1e-6));
        // the stale 0.9 must have fallen below the default 0.5 active threshold
        assertThat(readings.get(1).effective()).isLessThan(0.5);

        // the read path is read-only: the anchor is never rewritten
        verify(misconceptionStates, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(misconceptionStates, never()).save(org.mockito.ArgumentMatchers.any(MisconceptionState.class));
    }

    @Test
    @DisplayName("stale refutation relaxes UP toward the prior — relapse is modelled, not dismissed")
    void staleRefutationDriftsUp() {
        UUID learnerId = UUID.randomUUID();
        Instant now = Instant.now();
        MisconceptionState refuted = row(0.05, now.minus(Duration.ofDays(365)));
        when(misconceptionStates.findByLearnerIdOrderByProbabilityDesc(learnerId))
                .thenReturn(List.of(refuted));

        MisconceptionReading r = service.misconceptionReadings(learnerId).get(0);

        assertThat(r.effective()).isGreaterThan(0.05).isLessThan(PRIOR + 1e-9);
    }
}

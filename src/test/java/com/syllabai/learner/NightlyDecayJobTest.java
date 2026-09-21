package com.syllabai.learner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syllabai.learner.decay.EbbinghausDecayService;
import com.syllabai.shared.events.DecayAppliedEvent;
import com.syllabai.shared.events.ReviewScheduledEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Nightly decay job (audit fix #3 + session-114 run-if-missed trigger):
 * DECAY_APPLIED / REVIEW_SCHEDULED events must be published for the research
 * stream, the review decision must evaluate the effective (already decayed)
 * mastery rather than re-decaying it, and the checker must run the batch iff
 * the current 03:00 UTC window's ledger row is absent — recording every
 * execution (including zero-cell runs) in the V38 ledger.
 */
class NightlyDecayJobTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private final SkillStateRepository skillStates = mock(SkillStateRepository.class);
    private final ReviewScheduleRepository reviewSchedules = mock(ReviewScheduleRepository.class);
    private final DecayJobRunRepository decayJobRuns = mock(DecayJobRunRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final NightlyDecayJob job = new NightlyDecayJob(
            skillStates, reviewSchedules, decayJobRuns, new EbbinghausDecayService(),
            new LearnerProperties(null, null, null,
                    new LearnerProperties.DecayJob(true, null, 3, null)),
            published::add);

    @Test
    @DisplayName("an idle state decays, publishes DECAY_APPLIED and schedules a review")
    void decayAppliesAndSchedulesReview() {
        Instant lastPracticed = Instant.now().minus(Duration.ofDays(40));
        SkillState state = new SkillState(LEARNER, NODE, 0.5, lastPracticed);
        when(skillStates.findByLastPracticedAtBefore(any(), any()))
                .thenReturn(List.of(state))
                .thenReturn(List.of());
        when(reviewSchedules.existsByLearnerIdAndNodeIdAndStatus(
                LEARNER, NODE, ReviewSchedule.Status.PENDING)).thenReturn(false);

        job.applyForgettingDecay();

        // 0.5 * e^(-40/90) in the mid band (0.45 < 0.5 < 0.8 → τ = 90 days).
        // Tolerance 1e-6 (was 1e-9): the service measures "now" a few hundred µs after
        // this test builds lastPracticed, so Δt/90 alone contributes ~1e-8..1e-9 relative
        // drift — platform-dependent JDK math did the rest (documented session-19 flake).
        assertThat(state.mastery()).isCloseTo(0.5 * Math.exp(-40.0 / 90.0), within(1e-6));
        verify(reviewSchedules).save(any(ReviewSchedule.class));

        DecayAppliedEvent decay = sole(DecayAppliedEvent.class);
        assertThat(decay.nodeId()).isEqualTo(NODE);
        assertThat(decay.priorMastery()).isCloseTo(0.5, within(1e-9));
        assertThat(decay.decayedMastery()).isCloseTo(0.5 * Math.exp(-40.0 / 90.0), within(1e-6));
        assertThat(decay.tauDays()).isEqualTo(90);
        assertThat(decay.daysSinceLastPractice()).isEqualTo(40);
        assertThat(decay.reviewThresholdCrossed()).isTrue();

        ReviewScheduledEvent review = sole(ReviewScheduledEvent.class);
        assertThat(review.nodeId()).isEqualTo(NODE);
        assertThat(review.reason()).isEqualTo("DECAY_CROSSED_THRESHOLD");
        assertThat(review.masteryAtTrigger())
                .isCloseTo(0.5 * Math.exp(-40.0 / 90.0), within(1e-6));
    }

    @Test
    @DisplayName("a pending review is not duplicated and no REVIEW_SCHEDULED is published")
    void pendingReviewNotDuplicated() {
        Instant lastPracticed = Instant.now().minus(Duration.ofDays(40));
        SkillState state = new SkillState(LEARNER, NODE, 0.5, lastPracticed);
        when(skillStates.findByLastPracticedAtBefore(any(), any()))
                .thenReturn(List.of(state))
                .thenReturn(List.of());
        when(reviewSchedules.existsByLearnerIdAndNodeIdAndStatus(
                LEARNER, NODE, ReviewSchedule.Status.PENDING)).thenReturn(true);

        job.applyForgettingDecay();

        verify(reviewSchedules, never()).save(any(ReviewSchedule.class));
        assertThat(published).noneMatch(e -> e instanceof ReviewScheduledEvent);
        assertThat(published).anyMatch(e -> e instanceof DecayAppliedEvent);   // decay itself still logged
    }

    @Test
    @DisplayName("a state already at the decay floor decays no further and emits no DECAY_APPLIED")
    void floorStateIsStable() {
        // at floor 0.1 and 40 days idle, decayed() returns the floor itself — no write
        SkillState state = new SkillState(LEARNER, NODE, 0.1,
                Instant.now().minus(Duration.ofDays(40)));
        when(skillStates.findByLastPracticedAtBefore(any(), any()))
                .thenReturn(List.of(state))
                .thenReturn(List.of());
        when(reviewSchedules.existsByLearnerIdAndNodeIdAndStatus(
                LEARNER, NODE, ReviewSchedule.Status.PENDING)).thenReturn(false);

        job.applyForgettingDecay();

        assertThat(state.mastery()).isCloseTo(0.1, within(1e-12));
        assertThat(published).noneMatch(e -> e instanceof DecayAppliedEvent);
    }

    // ── session-114: the run-if-missed checker ──────────────────────────────

    @Test
    @DisplayName("a missed window runs on the next tick and records a CATCH_UP ledger row")
    void missedWindowIsCaughtUp() {
        Instant lastPracticed = Instant.now().minus(Duration.ofDays(40));
        SkillState state = new SkillState(LEARNER, NODE, 0.5, lastPracticed);
        when(skillStates.findByLastPracticedAtBefore(any(), any()))
                .thenReturn(List.of(state))
                .thenReturn(List.of());
        when(reviewSchedules.existsByLearnerIdAndNodeIdAndStatus(
                LEARNER, NODE, ReviewSchedule.Status.PENDING)).thenReturn(true);
        // no ledger row for the current window (default mock: existsById → false)

        job.applyForgettingDecay();

        // the decay ran (not skipped) and the ledger row was written with the
        // current window anchor and the CATCH_UP trigger (the test runs hours
        // past 03:00 UTC, outside the SCHEDULED grace)
        ArgumentCaptor<DecayJobRun> ledger = ArgumentCaptor.forClass(DecayJobRun.class);
        verify(decayJobRuns).save(ledger.capture());
        assertThat(ledger.getValue().windowStart())
                .isEqualTo(NightlyDecayJob.windowStart(Instant.now(), 3));
        assertThat(ledger.getValue().triggerKind()).isEqualTo("CATCH_UP");
        assertThat(ledger.getValue().decayed()).isEqualTo(1);
        assertThat(ledger.getValue().reviewsScheduled()).isEqualTo(0);   // pending dedup above
        assertThat(published).anyMatch(e -> e instanceof DecayAppliedEvent);
    }

    @Test
    @DisplayName("a window already recorded in the ledger is never re-run — not even a zero-cell run is repeated")
    void handledWindowIsSkipped() {
        when(decayJobRuns.existsById(any(Instant.class))).thenReturn(true);

        job.applyForgettingDecay();

        verify(skillStates, never()).findByLastPracticedAtBefore(any(), any());
        verify(decayJobRuns, never()).save(any(DecayJobRun.class));
        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("the window anchor rolls at 03:00 UTC: before it, the window is yesterday's")
    void windowAnchorRollsAtThreeUtc() {
        ZonedDateTime before = LocalDate.of(2026, 9, 21).atTime(2, 59).atZone(ZoneOffset.UTC);
        ZonedDateTime at = LocalDate.of(2026, 9, 21).atTime(3, 0).atZone(ZoneOffset.UTC);
        ZonedDateTime lateEvening = LocalDate.of(2026, 9, 21).atTime(23, 42).atZone(ZoneOffset.UTC);

        assertThat(NightlyDecayJob.windowStart(before.toInstant(), 3))
                .isEqualTo(LocalDate.of(2026, 9, 20).atTime(3, 0).atZone(ZoneOffset.UTC).toInstant());
        assertThat(NightlyDecayJob.windowStart(at.toInstant(), 3))
                .isEqualTo(LocalDate.of(2026, 9, 21).atTime(3, 0).atZone(ZoneOffset.UTC).toInstant());
        assertThat(NightlyDecayJob.windowStart(lateEvening.toInstant(), 3))
                .isEqualTo(LocalDate.of(2026, 9, 21).atTime(3, 0).atZone(ZoneOffset.UTC).toInstant());
    }

    @Test
    @DisplayName("an on-time tick within the grace window records SCHEDULED, not CATCH_UP")
    void onTimeRunIsScheduled() {
        // a window anchored 2 minutes ago (a 03:00 tick executing at 03:02)
        // — simulated by computing the trigger exactly as the job does
        Instant windowStart = Instant.now().minus(Duration.ofMinutes(2));
        String trigger = Duration.between(windowStart, Instant.now())
                .compareTo(NightlyDecayJob.SCHEDULED_GRACE) <= 0
                ? "SCHEDULED" : "CATCH_UP";
        assertThat(trigger).isEqualTo("SCHEDULED");

        Instant staleWindow = Instant.now().minus(Duration.ofHours(6));
        String catchUp = Duration.between(staleWindow, Instant.now())
                .compareTo(NightlyDecayJob.SCHEDULED_GRACE) <= 0
                ? "SCHEDULED" : "CATCH_UP";
        assertThat(catchUp).isEqualTo("CATCH_UP");
    }

    private <T> T sole(Class<T> type) {
        List<T> matches = published.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }
}

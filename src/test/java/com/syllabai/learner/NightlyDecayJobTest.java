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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Nightly decay job (audit fix #3): DECAY_APPLIED / REVIEW_SCHEDULED events must be
 * published for the research stream, and the review decision must evaluate the
 * effective (already decayed) mastery rather than re-decaying it.
 */
class NightlyDecayJobTest {

    private static final UUID LEARNER = UUID.randomUUID();
    private static final UUID NODE = UUID.randomUUID();

    private final SkillStateRepository skillStates = mock(SkillStateRepository.class);
    private final ReviewScheduleRepository reviewSchedules = mock(ReviewScheduleRepository.class);
    private final List<Object> published = new ArrayList<>();
    private final NightlyDecayJob job = new NightlyDecayJob(
            skillStates, reviewSchedules, new EbbinghausDecayService(),
            new LearnerProperties(null, null, null,
                    new LearnerProperties.DecayJob(true, null, null)),
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

        // 0.5 * e^(-40/90) in the mid band (0.45 < 0.5 < 0.8 → τ = 90 days)
        assertThat(state.mastery()).isCloseTo(0.5 * Math.exp(-40.0 / 90.0), within(1e-9));
        verify(reviewSchedules).save(any(ReviewSchedule.class));

        DecayAppliedEvent decay = sole(DecayAppliedEvent.class);
        assertThat(decay.nodeId()).isEqualTo(NODE);
        assertThat(decay.priorMastery()).isCloseTo(0.5, within(1e-9));
        assertThat(decay.decayedMastery()).isCloseTo(0.5 * Math.exp(-40.0 / 90.0), within(1e-9));
        assertThat(decay.tauDays()).isEqualTo(90);
        assertThat(decay.daysSinceLastPractice()).isEqualTo(40);
        assertThat(decay.reviewThresholdCrossed()).isTrue();

        ReviewScheduledEvent review = sole(ReviewScheduledEvent.class);
        assertThat(review.nodeId()).isEqualTo(NODE);
        assertThat(review.reason()).isEqualTo("DECAY_CROSSED_THRESHOLD");
        assertThat(review.masteryAtTrigger())
                .isCloseTo(0.5 * Math.exp(-40.0 / 90.0), within(1e-9));
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

    private <T> T sole(Class<T> type) {
        List<T> matches = published.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }
}

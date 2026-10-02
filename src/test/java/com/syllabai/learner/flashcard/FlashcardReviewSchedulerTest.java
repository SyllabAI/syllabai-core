package com.syllabai.learner.flashcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syllabai.learner.FlashcardRating;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The flashcard review-ladder pins (T-C53) — pure arithmetic, constants
 * hand-derived from the hub's shipped lib/flashcard-review.ts semantics
 * (tranche 4.8), not from the implementation under test:
 *
 * <ul>
 *   <li>ladder boundaries — streak 1→1d … 5→16d, streak 6→32d, and every
 *       streak ≥ 6 stays on the 32d maintenance cap;</li>
 *   <li>"still learning" tail → due immediately (streak 0, interval 0);</li>
 *   <li>re-rate resets the clock — a STILL_LEARNING anywhere in the tail
 *       drops the streak to the run after it;</li>
 *   <li>due boundary — now == dueAt is DUE (a card is not kept overdue by a
 *       strict comparison);</li>
 *   <li>lenient params — null/empty/malformed ladders fall back to the
 *       shipped default;</li>
 *   <li>the trail contract is load-bearing — an empty trail is refused, not
 *       silently scheduled.</li>
 * </ul>
 */
class FlashcardReviewSchedulerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");
    private static final FlashcardReviewScheduler SCHEDULER = new FlashcardReviewScheduler();
    private static final FlashcardReviewParams LADDER = new FlashcardReviewParams(null);

    private static FlashcardRating know(int minutesAfterT0) {
        return new FlashcardRating(null, null, "fl_x",
                FlashcardRating.Rating.KNOW, T0.plus(Duration.ofMinutes(minutesAfterT0)));
    }

    private static FlashcardRating stillLearning(int minutesAfterT0) {
        return new FlashcardRating(null, null, "fl_x",
                FlashcardRating.Rating.STILL_LEARNING, T0.plus(Duration.ofMinutes(minutesAfterT0)));
    }

    @Test
    @DisplayName("ladder boundaries: 1·2·4·8·16 then the 32d cap holds for every longer streak")
    void ladderBoundaries() {
        List<FlashcardRating> allKnow = List.of(
                know(0), know(10), know(20), know(30), know(40), know(50),
                know(60), know(70), know(80), know(90), know(100), know(110));

        assertThat(LADDER.intervalDaysFor(1)).isEqualTo(1);
        assertThat(LADDER.intervalDaysFor(2)).isEqualTo(2);
        assertThat(LADDER.intervalDaysFor(3)).isEqualTo(4);
        assertThat(LADDER.intervalDaysFor(4)).isEqualTo(8);
        assertThat(LADDER.intervalDaysFor(5)).isEqualTo(16);
        assertThat(LADDER.intervalDaysFor(6)).isEqualTo(32);
        assertThat(LADDER.intervalDaysFor(7)).isEqualTo(32);
        assertThat(LADDER.intervalDaysFor(12)).isEqualTo(32);
        assertThat(LADDER.intervalDaysFor(0)).isEqualTo(0);

        // end-to-end through the scheduler: a 12-know tail schedules on the cap
        var capped = SCHEDULER.scheduleCard("fl_cap", allKnow, T0, LADDER);
        assertThat(capped.streak()).isEqualTo(12);
        assertThat(capped.intervalDays()).isEqualTo(32);
        assertThat(capped.dueAt()).isEqualTo(T0.plusSeconds(110 * 60L).plus(Duration.ofDays(32)));
    }

    @Test
    @DisplayName("still-learning tail: due immediately, dueAt == lastRatedAt")
    void stillLearningIsDueNow() {
        var schedule = SCHEDULER.scheduleCard(
                "fl_sl", List.of(know(0), know(60), stillLearning(120)), T0.plusSeconds(121 * 60L), LADDER);
        assertThat(schedule.streak()).isZero();
        assertThat(schedule.intervalDays()).isZero();
        assertThat(schedule.rating()).isEqualTo(FlashcardRating.Rating.STILL_LEARNING);
        assertThat(schedule.dueAt()).isEqualTo(T0.plusSeconds(120 * 60L));
        assertThat(schedule.due()).isTrue();
    }

    @Test
    @DisplayName("re-rate resets the clock: knows, then still-learning, then know again → streak restarts at 1")
    void reRateResetsTheClock() {
        var schedule = SCHEDULER.scheduleCard(
                "fl_reset",
                List.of(know(0), know(10), stillLearning(20), know(30)),
                T0.plusSeconds(31 * 60L), LADDER);
        assertThat(schedule.streak()).isEqualTo(1);
        assertThat(schedule.intervalDays()).isEqualTo(1);
        assertThat(schedule.dueAt()).isEqualTo(T0.plusSeconds(30 * 60L).plus(Duration.ofDays(1)));
        assertThat(schedule.due()).isFalse();
    }

    @Test
    @DisplayName("due boundary: now == dueAt is due (never kept overdue by a strict compare)")
    void dueAtBoundaryIsDue() {
        var schedule = SCHEDULER.scheduleCard("fl_b", List.of(know(0)), T0.plus(Duration.ofDays(1)), LADDER);
        assertThat(schedule.dueAt()).isEqualTo(T0.plus(Duration.ofDays(1)));
        assertThat(schedule.due()).isTrue();
    }

    @Test
    @DisplayName("fresh know: not due before the interval elapses")
    void freshKnowNotDue() {
        var schedule = SCHEDULER.scheduleCard("fl_fresh", List.of(know(0)), T0.plusSeconds(1), LADDER);
        assertThat(schedule.due()).isFalse();
        assertThat(schedule.dueAt()).isEqualTo(T0.plus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("current rating is the trail tail; nodeId comes from the LATEST event")
    void tailCarriesCurrentRatingAndAnchor() {
        var latest = stillLearning(120);
        var schedule = SCHEDULER.scheduleCard(
                "fl_tail", List.of(know(0), know(60), latest), T0.plusSeconds(121 * 60L), LADDER);
        assertThat(schedule.rating()).isEqualTo(FlashcardRating.Rating.STILL_LEARNING);
        assertThat(schedule.lastRatedAt()).isEqualTo(latest.occurredAt());
    }

    @Test
    @DisplayName("lenient params: null, empty and malformed ladders fall back to the shipped default")
    void lenientParams() {
        assertThat(new FlashcardReviewParams(null).intervalDays())
                .isEqualTo(FlashcardReviewParams.DEFAULT_INTERVAL_DAYS);
        assertThat(new FlashcardReviewParams(List.of()).intervalDays())
                .isEqualTo(FlashcardReviewParams.DEFAULT_INTERVAL_DAYS);
        assertThat(new FlashcardReviewParams(List.of(0, 2)).intervalDays())
                .isEqualTo(FlashcardReviewParams.DEFAULT_INTERVAL_DAYS);
        assertThat(new FlashcardReviewParams(List.of(1, -4)).intervalDays())
                .isEqualTo(FlashcardReviewParams.DEFAULT_INTERVAL_DAYS);
        // a well-formed custom ladder is honored: streaks beyond its length ride the cap
        var shortLadder = new FlashcardReviewParams(List.of(3));
        assertThat(shortLadder.intervalDaysFor(1)).isEqualTo(3);
        assertThat(shortLadder.intervalDaysFor(2)).isEqualTo(3);
        assertThat(shortLadder.intervalDaysFor(9)).isEqualTo(3);
    }

    @Test
    @DisplayName("empty trail is refused — never-rated cards have no schedule (nothing is invented)")
    void emptyTrailRefused() {
        assertThatThrownBy(() -> SCHEDULER.scheduleCard("fl_none", List.of(), T0, LADDER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fl_none");
    }
}

package com.syllabai.learner.flashcard;

import java.util.List;

/**
 * Flashcard review-ladder parameters (T-C53 — the V47 rating trail's reserved
 * consumer, ADR-029 tranche lineage). Mirrors the hub's shipped arithmetic
 * ({@code syllabai-hub lib/flashcard-review.ts}, tranche 4.8) bit-for-bit so a
 * card comes due on the same day whichever side derives the schedule.
 *
 * <p>The ladder — expanding Ebbinghaus spacing by consecutive-"know" streak:
 * a "still learning" tail is due immediately (the learner said they haven't
 * got it yet); a know-streak of n resurfaces after
 * {@code intervalDays[min(n-1, size-1)]} days, so the last entry is the
 * maintenance cap (mature cards keep a slow heartbeat — no graduation
 * fiction). Every re-rate resets the clock by construction: a "still
 * learning" anywhere in the tail drops the streak to 0.</p>
 *
 * <p>Research-design parameters, versioned through the {@code model_versions}
 * registry ({@code learner.flashcard-review}, V59) and configurable via
 * {@code syllabai.learner.flashcard-review.interval-days} — never hard-coded
 * in domain logic. Lenient normalization follows the {@code LearnerProperties}
 * convention (Bkt/Decay/Bdt): a missing, empty or malformed ladder falls back
 * to the shipped default rather than failing the read.</p>
 *
 * <p>Doctrine guards (both pinned by tests): the schedule this feeds is
 * COMPUTED AT READ from the append-only {@code flashcard_ratings} trail and
 * never persisted (ADR-031 — decay/scheduling is derived, never stored); and
 * it is TIMING ONLY — ratings never touch BKT/SkillState/misconception state
 * (the learner-model honesty rule, pinned by FlashcardRatingFlowIT).</p>
 */
public record FlashcardReviewParams(List<Integer> intervalDays) {

    /** The shipped ladder (hub parity): 1·2·4·8·16 days, then a capped 32-day
     *  maintenance cycle. Trail caps cannot understate an interval — any
     *  streak ≥ the ladder length maps to the cap. */
    public static final List<Integer> DEFAULT_INTERVAL_DAYS = List.of(1, 2, 4, 8, 16, 32);

    public FlashcardReviewParams {
        if (intervalDays == null || intervalDays.isEmpty()
                || intervalDays.stream().anyMatch(d -> d == null || d <= 0)) {
            intervalDays = DEFAULT_INTERVAL_DAYS;
        }
        intervalDays = List.copyOf(intervalDays);
    }

    /**
     * Days before the next review for a consecutive-"know" streak.
     * streak 0 ("still learning" tail) → 0 — due immediately.
     *
     * @param knowStreak length of the trailing run of KNOW ratings (≥ 0)
     */
    public int intervalDaysFor(int knowStreak) {
        if (knowStreak <= 0) return 0;
        return intervalDays.get(Math.min(knowStreak - 1, intervalDays.size() - 1));
    }
}

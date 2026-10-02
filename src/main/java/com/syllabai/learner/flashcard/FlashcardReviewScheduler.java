package com.syllabai.learner.flashcard;

import com.syllabai.learner.FlashcardRating;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The flashcard review schedule — derived, never stored (T-C53; the consumer
 * V47's trail was explicitly designed for: "the trail preserves the re-rating
 * history the future Ebbinghaus review-scheduler will consume").
 *
 * <p>Pure and stateless (the BktEngine pattern): the caller supplies the
 * append-only rating trail of ONE card in chronological order (oldest first —
 * the repository's card-grouped, occurred-at-ascending read) plus the read
 * instant, and gets the card's schedule. Nothing here writes anything: no
 * {@code review_schedules} row (that table is the marked-attempt spec-point
 * queue and stays attempts-derived per ADR-031's scope guard), no skill or
 * misconception state — the honesty rule keeps ratings a TIMING-ONLY
 * self-report evidence class (FlashcardRatingFlowIT).</p>
 *
 * <p>Derivation (hub parity with {@code syllabai-hub lib/flashcard-review.ts},
 * tranche 4.8): the streak is the trailing run of consecutive KNOW ratings;
 * a "still learning" tail means streak 0 — due immediately. The interval is
 * {@link FlashcardReviewParams#intervalDaysFor(int)}; {@code dueAt} is the
 * latest rating's {@code occurred_at} plus that many days; {@code due} is
 * {@code !now.isBefore(dueAt)}. A re-rate resets the clock by construction.
 * Trail length cannot overstate an interval: any streak ≥ the ladder length
 * maps to the cap, so the hub's bounded (TRAIL_CAP 10) trail and this full
 * trail derive the SAME interval — the reported streak may differ (core sees
 * the true tail run), the due date does not.</p>
 *
 * <p>Ordering contract: {@code trail} MUST be chronological (oldest first)
 * for the single card named by {@code cardId}; the controller derives that
 * from the repository's {@code ORDER BY card_id, occurred_at, id} read.
 * occurred_at ties within one card are a production non-event (the rating
 * flow IT paces writes like a human deck flip); the id tiebreaker keeps the
 * read deterministic regardless.</p>
 */
public final class FlashcardReviewScheduler {

    /** One card's derived schedule — a view over the trail, never a row. */
    public record CardSchedule(
            String cardId,
            /** the deck-anchor node the card's ratings attribute to */
            UUID nodeId,
            /** the card's current rating (the trail tail) */
            FlashcardRating.Rating rating,
            /** consecutive KNOW ratings since the last STILL_LEARNING */
            int streak,
            /** when the latest rating was recorded */
            Instant lastRatedAt,
            /** when the card came (or comes) due for its next review */
            Instant dueAt,
            /** whether the card is due at the read instant */
            boolean due,
            /** days between the latest rating and the next review (0 = due now) */
            int intervalDays) {
    }

    /**
     * Derive one card's schedule from its chronological rating trail.
     *
     * @param cardId the hub content id ("fl_*") — echoed, hub-owned identity
     * @param trail  the card's rating events, oldest first (non-empty)
     * @param now    the read instant (computed at read; never persisted)
     * @param params the ladder (lenient-normalized)
     */
    public CardSchedule scheduleCard(String cardId, List<FlashcardRating> trail, Instant now,
                                     FlashcardReviewParams params) {
        if (trail.isEmpty()) {
            throw new IllegalArgumentException(
                    "empty trail for card " + cardId + " — never-rated cards have no schedule");
        }
        FlashcardRating latest = trail.get(trail.size() - 1);
        int streak = 0;
        for (int i = trail.size() - 1; i >= 0; i--) {
            if (trail.get(i).rating() != FlashcardRating.Rating.KNOW) break;
            streak += 1;
        }
        int intervalDays = params.intervalDaysFor(streak);
        Instant dueAt = latest.occurredAt().plus(Duration.ofDays(intervalDays));
        boolean due = !now.isBefore(dueAt);
        return new CardSchedule(cardId, latest.nodeId(), latest.rating(), streak,
                latest.occurredAt(), dueAt, due, intervalDays);
    }
}

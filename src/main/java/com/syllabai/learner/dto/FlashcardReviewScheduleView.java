package com.syllabai.learner.dto;

import com.syllabai.learner.flashcard.FlashcardReviewScheduler.CardSchedule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The flashcard review-schedule feed (T-C53 — GET
 * /api/v1/learners/me/flashcard-review-schedule): the Ebbinghaus queue derived
 * at READ from the learner's append-only flashcard_ratings trail (V47).
 *
 * <p>Contract notes:</p>
 * <ul>
 *   <li>{@code rating} uses the hub's wire vocabulary ("still-learning" /
 *       "know"), same convention as {@link FlashcardRatingView}.</li>
 *   <li>{@code subtopicCode} echoes the resolved deck anchor's curriculum
 *       code ("4CH1-S1-a") for human display; null when the anchor node row
 *       is absent (clients attribute via {@code nodeId} / their own content
 *       bridge — nothing is invented).</li>
 *   <li>{@code cards} is ordered by {@code dueAt} then {@code cardId} — due
 *       cards surface first, stalest due date first, the order a learner
 *       should work them.</li>
 *   <li>{@code summary} mirrors the hub drawer's queue summary: due /
 *       scheduled counts and the earliest future due date (null when nothing
 *       is scheduled).</li>
 * </ul>
 *
 * <p>Evidence-class semantics (unchanged from V47): this feed is TIMING ONLY.
 * It is computed from the trail on every read and persisted nowhere (ADR-031
 * doctrine — the {@code review_schedules} table stays the marked-attempt
 * spec-point queue); a due card never implies mastery, and rated cards' spec
 * points stay "Not measured" wherever mastery is served.</p>
 */
public record FlashcardReviewScheduleView(
        UUID learnerId,
        Instant generatedAt,
        Summary summary,
        List<CardScheduleView> cards) {

    public record CardScheduleView(
            String cardId,
            String subtopicCode,
            UUID nodeId,
            String rating,
            int streak,
            Instant lastRatedAt,
            Instant dueAt,
            boolean due,
            int intervalDays) {

        public static CardScheduleView from(CardSchedule s, Map<UUID, String> nodeCodes) {
            return new CardScheduleView(
                    s.cardId(), nodeCodes.get(s.nodeId()), s.nodeId(),
                    s.rating().name().toLowerCase().replace('_', '-'),
                    s.streak(), s.lastRatedAt(), s.dueAt(), s.due(), s.intervalDays());
        }
    }

    /** due = cards due at the read instant; scheduled = on a future schedule;
     *  nextDueAt = the earliest future due date (null when nothing is due later). */
    public record Summary(long due, long scheduled, Instant nextDueAt) {
    }
}

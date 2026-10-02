package com.syllabai.learner.dto;

import com.syllabai.learner.FlashcardRating;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One bounded page of the learner's raw flashcard-rating trail (T-C61 — GET
 * /api/v1/learners/me/flashcard-rating-trail), the contract that lets the
 * hub TRUE-MERGE cross-device trails instead of picking a newest-evidence
 * winner (T-C57's recorded limit). The rows are the append-only V47 events
 * verbatim — this view is a READ, persisted nowhere (ADR-031 doctrine
 * extends trivially: serving raw evidence writes nothing).
 *
 * <p>Order contract (pinned by FlashcardRatingTrailFlowIT): {@code events}
 * is newest first in the keyset order {@code (occurred_at DESC, id DESC)}
 * — the same total order the cursor walks, so a client that concatenates
 * pages behind the cursor gets the trail gap-free and duplicate-free,
 * including occurred_at ties (the id is the deterministic tiebreaker).</p>
 *
 * <p>Wire vocabulary mirrors FlashcardRatingView: {@code rating} is the
 * hub's form ("still-learning" / "know"); {@code subtopicCode} echoes the
 * resolved deck anchor for client-side sanity (honest null when the node
 * row is absent — the rating row itself was already fail-closed validated
 * at write time, so the null degrades display only, never attribution).</p>
 *
 * @param events     this page's events, newest first
 * @param nextCursor opaque keyset cursor to the NEXT page; null when this
 *                   page reached the end of the trail
 * @param hasMore    true when another page exists (nextCursor is then
 *                   non-null — the two are set together by the controller)
 * @param generatedAt the read instant (computed at read, never persisted)
 */
public record FlashcardRatingTrailView(
        List<Event> events,
        String nextCursor,
        boolean hasMore,
        Instant generatedAt) {

    /** One raw rating event as served to the learner's own client. */
    public record Event(
            String cardId,
            String rating,
            String subtopicCode,
            UUID nodeId,
            Instant occurredAt) {

        public static Event from(FlashcardRating r, String subtopicCode) {
            return new Event(r.cardId(), r.rating().name().toLowerCase()
                    .replace('_', '-'), subtopicCode, r.nodeId(), r.occurredAt());
        }
    }
}

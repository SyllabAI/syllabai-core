package com.syllabai.learner.dto;

import com.syllabai.learner.FlashcardRating;
import java.time.Instant;
import java.util.UUID;

/**
 * One flashcard rating event as served to clients (V47, ADR-029 tranche
 * 4.4). {@code rating} uses the hub's wire vocabulary ("still-learning" /
 * "know"); {@code subtopicCode} echoes the resolved deck anchor on the
 * POST response (null on the learner-state slice — clients attribute via
 * the node id or their own content bridge).
 */
public record FlashcardRatingView(
        String cardId, String rating, String subtopicCode, UUID nodeId, Instant occurredAt) {

    public static FlashcardRatingView from(FlashcardRating r, String subtopicCode) {
        return new FlashcardRatingView(r.cardId(), r.rating().name().toLowerCase()
                .replace('_', '-'), subtopicCode, r.nodeId(), r.occurredAt());
    }
}

package com.syllabai.learner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One flashcard rating event (V47 — the "flashcard ratings → core evidence
 * class" contract, ADR-029 tranche 4.4). Append-only: every Still learning /
 * Know action from the hub's deck player appends a row; the latest row per
 * {@code (learner_id, card_id)} is the card's current rating and the trail
 * preserves the re-rating history a future review scheduler consumes.
 *
 * <p>Evidence class semantics — what this row deliberately is NOT:</p>
 * <ul>
 *   <li>NEVER mastery: no BKT update, no {@link SkillState} row. Rating a
 *       card is self-report (the learner-model honesty rule); mastery comes
 *       from marked attempts only. Pinned by FlashcardRatingFlowIT, which
 *       asserts {@code SkillStateRepository} stays empty after ratings.</li>
 *   <li>NEVER misconception evidence: the BDT engine reads assessment
 *       distractors, not recall confidence.</li>
 *   <li>NOT a review-schedule write: the Ebbinghaus queue consumes rating
 *       trails in a later tranche; nothing here pre-empts its design.</li>
 * </ul>
 *
 * <p>Attribution: {@code nodeId} is the curriculum-structure knowledge node
 * the deck is anchored on (e.g. 4CH1-S1-a — on the production 4CH1 graph
 * that level ingests as TOPIC), resolved server-side against the ingested
 * curriculum — the controller refuses unknown codes, semantic-layer nodes
 * (CONCEPT/MISCONCEPTION) and the subject root, so a row can never claim a
 * node the curriculum does not have. {@code cardId} is the hub
 * content id ("fl_*") kept as an opaque external reference: the hub owns
 * card identity, core owns the learner model.</p>
 */
@Entity
@Table(name = "flashcard_ratings")
public class FlashcardRating {

    public enum Rating {
        STILL_LEARNING, KNOW;

        /** tolerant parse for the hub's lowercase wire form ("still-learning") */
        public static Rating parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase().replace('_', '-')) {
                case "still-learning", "stilllearning" -> STILL_LEARNING;
                case "know" -> KNOW;
                default -> null;
            };
        }
    }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    /** the curriculum-structure node the deck is anchored on (resolved, fail-closed) */
    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    /** hub content id ("fl_*") — opaque external reference, hub-owned identity */
    @Column(name = "card_id", nullable = false, length = 64)
    private String cardId;

    @Column(name = "rating", nullable = false, length = 16)
    private String rating;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FlashcardRating() {
        // JPA
    }

    public FlashcardRating(UUID learnerId, UUID nodeId, String cardId, Rating rating,
                           Instant occurredAt) {
        this.learnerId = learnerId;
        this.nodeId = nodeId;
        this.cardId = cardId;
        this.rating = rating.name();
        this.occurredAt = occurredAt;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (occurredAt == null) occurredAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID learnerId() { return learnerId; }
    public UUID nodeId() { return nodeId; }
    public String cardId() { return cardId; }
    public Rating rating() { return Rating.valueOf(rating); }
    public Instant occurredAt() { return occurredAt; }
    public Instant createdAt() { return createdAt; }
}

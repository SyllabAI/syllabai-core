-- V47: flashcard ratings — the "flashcard ratings → core evidence class"
-- contract (ADR-029 tranche 4.4, operator trace 1a0e8ebb1f436dbd).
--
-- CONTEXT: the hub's flashcard decks have always recorded ratings, but only
-- to the browser-local SIMULATED overlay (localStorage). The real learner
-- model (attempts → BKT → decay → review queue) never saw them, and the
-- core-backed My State drawer hard-coded flashcards: 0 with the comment
-- "flashcard ratings are a tracked gap".
--
-- DESIGN RULES (mirroring V21's evidence discipline):
--   - APPEND-ONLY event log: every rating action is a row; the latest row
--     per card is the card's current rating, and the trail preserves the
--     re-rating history the future Ebbinghaus review-scheduler will consume
--     ("still-learning" again after decay is exactly the signal it needs).
--   - ATTRIBUTION IS RESOLVED, NOT CLAIMED: the hub sends the deck's
--     subtopic anchor (the same RULE_DERIVED anchor that drives deck
--     placement, e.g. "4CH1-S1-a"); the controller resolves it against the
--     ingested curriculum knowledge graph and refuses unknown codes (422).
--     Nothing can attribute a rating to a node the curriculum does not have.
--   - SELF-REPORT, NEVER MASTERY: no BKT update, no SkillState row, no
--     misconception evidence, no ReviewSchedule. Ratings are an exposure /
--     history / stats evidence class only — the learner-model honesty rule
--     ("reading notes or rating flashcards is self-report") is pinned by the
--     FlashcardRatingFlowIT, which asserts SkillStateRepository stays empty.
--   - card_id is the hub content id ("fl_*") stored as an opaque external
--     reference — core has no flashcard registry and deliberately does not
--     pretend to; the hub owns card identity, core owns the learner model.
--
-- SCALE NOTE: one small row per deck-card flip, learner-scoped indexes only.

CREATE TABLE flashcard_ratings (
    id          UUID PRIMARY KEY,
    learner_id  UUID NOT NULL,
    node_id     UUID NOT NULL,
    card_id     VARCHAR(64) NOT NULL,
    rating      VARCHAR(16) NOT NULL CHECK (rating IN ('STILL_LEARNING', 'KNOW')),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_fr_learner_recent ON flashcard_ratings (learner_id, occurred_at DESC);
CREATE INDEX ix_fr_learner_card   ON flashcard_ratings (learner_id, card_id);

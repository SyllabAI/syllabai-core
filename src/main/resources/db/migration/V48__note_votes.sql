-- V48: note votes — the "note votes → core evidence class" contract
-- (ADR-029 tranche 4.9, operator trace 1a0e95906dd17959).
--
-- CONTEXT: the hub's revision notes have always carried the SME-style
-- "Was this revision note helpful?" micro-feedback (research §5.4), but the
-- vote lived only in the browser-local SIMULATED overlay
-- (progress.notesRead[noteId].helpful). Core already tracks note VIEWS
-- (revision_note_viewed); the learner model never saw the quality signal.
-- This closes the tracked gap named in the hub's note-footnote docblock
-- ("core has no votes contract yet").
--
-- DESIGN RULES (mirroring V47's flashcard_ratings discipline exactly):
--   - APPEND-ONLY event log: every vote action is a row; the latest row per
--     note is the note's current vote, and a vote CHANGE is new evidence
--     (the trail preserves the change, not just the tail state).
--   - ATTRIBUTION IS RESOLVED, NOT CLAIMED: the hub sends the note's
--     subtopic anchor (the same RULE_DERIVED anchor family that drives
--     placement — 112/112 pilot notes resolve to "4CH1-S#-#" codes); the
--     controller resolves it against the ingested curriculum knowledge
--     graph with the SAME structural gate as ratings (curriculum-structure
--     node below the subject root: UNIT/TOPIC/SUBTOPIC). Unknown codes, the
--     subject root and the semantic layer (CONCEPT/MISCONCEPTION) are 404
--     and nothing is written.
--   - SELF-REPORT, NEVER MASTERY: no BKT update, no SkillState row, no
--     misconception evidence, no ReviewSchedule. A note vote is an
--     exposure / history / stats evidence class only — pinned by
--     NoteVoteFlowIT, which asserts SkillStateRepository stays empty.
--   - note_id is the hub content id ("rn_*") stored as an opaque external
--     reference — core has no note registry and deliberately does not
--     pretend to; the hub owns note identity, core owns the learner model.
--
-- SCALE NOTE: one small row per vote action, learner-scoped indexes only.

CREATE TABLE note_votes (
    id          UUID PRIMARY KEY,
    learner_id  UUID NOT NULL,
    node_id     UUID NOT NULL,
    note_id     VARCHAR(64) NOT NULL,
    vote        VARCHAR(16) NOT NULL CHECK (vote IN ('HELPFUL', 'NOT_HELPFUL')),
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_nv_learner_recent ON note_votes (learner_id, occurred_at DESC);
CREATE INDEX ix_nv_learner_note   ON note_votes (learner_id, note_id);

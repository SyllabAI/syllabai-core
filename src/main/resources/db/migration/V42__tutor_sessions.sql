-- V42 — tutor sessions: the Spec §22 server-side transcript store (s140).
--
-- The s139 working-memory design held the transcript client-side and passed it
-- per-ask, and explicitly deferred server-side persistence to the §22
-- tutor/sessions endpoints. This migration is those endpoints' substrate:
--
--   tutor_sessions      one row per chat (learner-owned, append-only lifecycle)
--   tutor_session_turns the transcript, newest appended with a per-session seq
--
-- Scope decisions (documented, not silent):
--   - This is the ONLY sanctioned server-side transcript surface (§22); it
--     stores the learner's own tutor chat, nothing else. CLA interactions are
--     NOT tutor sessions (their V24 engagement rows + ClaInteractionEvent
--     telemetry already carry that surface's record).
--   - The assistant turn stores the answer with citation markers REMOVED —
--     the same ConversationTurn sanitization used for s139 history: [n]
--     numbers belong to SOURCES absent from any later rendering, and the
--     citation archive of record stays the immutable KA_RAG_COMPLETED
--     telemetry row. Turn content is the learner-visible prose.
--   - §19 traceability rides each assistant turn (model, provider, latency,
--     evidence count, refusal flag) so a hydrated transcript renders the same
--     honest footer the live answer showed, without reconstructing citations.
--   - Ownership is enforced in the service layer: a session id belonging to
--     another learner is indistinguishable from an unknown id (404).
--
-- Privacy posture (§20, data minimization for a minors' pilot): academic
-- transcript only — no personal-life facts, no cross-learner data. Deletion
-- follows the learner account (cascade), same as every learner-owned row.

CREATE TABLE tutor_sessions (
    id              UUID PRIMARY KEY,
    learner_id      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    last_active_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_tutor_sessions_learner_active
    ON tutor_sessions (learner_id, last_active_at DESC);

CREATE TABLE tutor_session_turns (
    id              UUID PRIMARY KEY,
    session_id      UUID NOT NULL REFERENCES tutor_sessions (id) ON DELETE CASCADE,
    seq             INT NOT NULL,
    role            VARCHAR(8) NOT NULL,          -- user | assistant (ConversationTurn roles)
    content         TEXT NOT NULL,                -- capped 4000 chars in service
    evidence_count  INT NOT NULL DEFAULT 0,
    refused         BOOLEAN NOT NULL DEFAULT FALSE,
    answer_model    VARCHAR(120),
    answer_provider VARCHAR(60),
    latency_ms      DOUBLE PRECISION,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_tutor_session_turn_seq UNIQUE (session_id, seq),
    CONSTRAINT ck_tutor_session_turn_role CHECK (role IN ('user', 'assistant'))
);

CREATE INDEX idx_tutor_session_turns_session
    ON tutor_session_turns (session_id, seq);

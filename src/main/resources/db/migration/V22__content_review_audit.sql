-- V22: durable audit trail for API-driven content-review mutations.
--
-- GAP (session-71 baseline): every promotion/rejection/flag/placement/map made
-- through the teacher API wrote only an ephemeral server log line. The T-C04
-- hash-chained teacher_validation_events table covers ONLY the gated importer
-- path; the API path (the one live reviewers actually use) had no durable
-- record — violating the "audit trail exists for every promotion" invariant.
--
-- This table is the API-path counterpart: append-only, written in the SAME
-- transaction as the mutation it describes. One row per state transition per
-- target (validate-all therefore writes one row per version/scheme it flips,
-- plus one for the paper).
--
-- Boundary rules (consistent with V18):
--   * never stores content, only decision metadata;
--   * from_state/to_state carry the validation_state names (PLACE/MAP_TOPICS
--     are factual associations that do not change validation state — their
--     states are NULL and the detail column carries the association);
--   * writes are additive; nothing in this table can change serving state.
--
-- History note: rows exist only from the moment this migration deployed.
-- Earlier API actions (2026-09-14 sessions) remain reconstructable from server
-- logs / git / the operator worklog; no backfill is fabricated.

CREATE TABLE content_review_audit (
    id             BIGSERIAL PRIMARY KEY,
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_user_id  UUID REFERENCES users (id) ON DELETE SET NULL,
    actor_label    VARCHAR(254) NOT NULL DEFAULT '',
    action         VARCHAR(24) NOT NULL
        CONSTRAINT ck_cra_action CHECK (action IN (
            'VALIDATE', 'VALIDATE_ALL', 'REJECT', 'FLAG', 'UNFLAG',
            'PLACE', 'MAP_TOPICS')),
    target_type    VARCHAR(24) NOT NULL
        CONSTRAINT ck_cra_target_type CHECK (target_type IN (
            'exam_paper', 'question_version', 'mark_scheme', 'question')),
    target_id      UUID NOT NULL,
    from_state     VARCHAR(12),
    to_state       VARCHAR(12),
    detail         TEXT NOT NULL DEFAULT ''
);

CREATE INDEX ix_cra_target ON content_review_audit (target_type, target_id);
CREATE INDEX ix_cra_occurred ON content_review_audit (occurred_at DESC);
CREATE INDEX ix_cra_actor ON content_review_audit (actor_user_id);

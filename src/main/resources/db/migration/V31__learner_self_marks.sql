-- V31: learner self-marking surface (ADR-026 SME practice tranche).
--
-- The Save-My-Exams-style self-mark flow: the learner reveals the validated
-- mark scheme after submitting a structured attempt and self-awards marks per
-- part. The settle/evidence mechanics mirror the teacher human-mark path
-- exactly (once-only BKT evidence, conservative full-marks-equals-correct
-- rule), but the provenance is kept structurally separate:
--
--   * answers/attempts gain the SELF_MARKED marking state (check constraints
--     extended — additive, existing rows unaffected);
--   * the self-assessment rows live in learner_self_marks, NEVER in
--     human_marks — the κ agreement sample (F-161) pairs Smart Mark runs with
--     TEACHER human marks only, so learner self-assessments cannot
--     contaminate it by construction.

-- ── extend the V8 marking-state check constraints ──────────────────────────
ALTER TABLE attempts DROP CONSTRAINT ck_attempt_marking_state;
ALTER TABLE attempts ADD CONSTRAINT ck_attempt_marking_state CHECK (marking_state IN
    ('AUTO_GRADED', 'PENDING', 'SMART_MARKED', 'HUMAN_MARKED', 'OVERRIDDEN', 'SELF_MARKED'));

ALTER TABLE answers DROP CONSTRAINT ck_answer_marking_state;
ALTER TABLE answers ADD CONSTRAINT ck_answer_marking_state CHECK (marking_state IN
    ('PENDING', 'SMART_MARKED', 'HUMAN_MARKED', 'OVERRIDDEN', 'SELF_MARKED'));

-- ── the self-assessment record (append-only, one row per part answer) ──────
CREATE TABLE learner_self_marks (
    id             UUID PRIMARY KEY,
    answer_id      UUID NOT NULL REFERENCES answers (id) ON DELETE CASCADE,
    learner_id     UUID NOT NULL,
    marks_awarded  INT NOT NULL CHECK (marks_awarded >= 0),
    comment        TEXT,
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_lsm_answer ON learner_self_marks (answer_id);
CREATE INDEX ix_lsm_learner ON learner_self_marks (learner_id);

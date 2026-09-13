-- T-C04 R3-5: teacher-validation review events.
--
-- Append-only audit of review/serving-state transitions applied by the GATED
-- IMPORTER from the staged hash-chained teacher decision log. Every row proves
-- WHO decided WHAT, from WHICH staged decision (seq + chain hash), written
-- WHERE (target + resulting state), and WHEN.
--
-- Boundary rules (AGENT.md §6 serving boundary; R3-5 directive):
--   * This table never stores content and NEVER touches ingestion evidence
--     (glm_ocr_bridge_records, documents, drafts, reconciliation stay frozen).
--   * Only validation_state columns on exam_papers / question_versions /
--     mark_schemes may change, and every change must have a row here first.
--   * FLAG decisions are review annotations: they do NOT change
--     validation_state (no new legal state was introduced); they are recorded
--     here with result_state = 'SUGGESTED' (unchanged serving state).
--   * REVERSE rows restore the pre-decision state (SUGGESTED) and reference
--     the reversed decision via decision_seq/decision_hash.
--   * UNIQUE (decision_seq, decision_hash) makes log replay idempotent: the
--     importer can never double-apply a decision.

CREATE TABLE teacher_validation_events (
    id              BIGSERIAL PRIMARY KEY,
    importer_run_id UUID NOT NULL,
    decision_seq    INT NOT NULL,
    decision_hash   CHAR(64) NOT NULL,
    action          VARCHAR(12) NOT NULL
        CONSTRAINT ck_tve_action CHECK (action IN ('VALIDATE', 'REJECT', 'FLAG', 'REVERSE')),
    target_type     VARCHAR(20) NOT NULL
        CONSTRAINT ck_tve_target_type CHECK (target_type IN ('question_version', 'mark_scheme', 'exam_paper')),
    target_id       UUID NOT NULL,
    target_label    TEXT NOT NULL DEFAULT '',
    reviewer        VARCHAR(80) NOT NULL,
    note            TEXT NOT NULL DEFAULT '',
    result_state    VARCHAR(12) NOT NULL
        CONSTRAINT ck_tve_result_state CHECK (result_state IN ('SUGGESTED', 'VALIDATED', 'REJECTED')),
    applied_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tve_decision UNIQUE (decision_seq, decision_hash),
    CONSTRAINT ck_tve_targets_differ CHECK (
        NOT (action = 'REVERSE' AND decision_seq <= 0)
    )
);

CREATE INDEX ix_tve_target ON teacher_validation_events (target_type, target_id);
CREATE INDEX ix_tve_run ON teacher_validation_events (importer_run_id);

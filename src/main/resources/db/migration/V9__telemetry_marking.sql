-- V9: telemetry event types for the marking loop (additive, Master Spec §15, §18).
-- The Cycle-1 six event types declared in V5 are unchanged; Smart Mark calibration
-- (Paper B) requires the marking decisions themselves to land in the append-only
-- research record. This is a documented extension, not a silent alteration:
--   SMART_MARK_COMPLETED — every smart-mark run (passed or failed validation)
--   HUMAN_MARK_RECORDED  — every teacher mark/override (the final truth)

ALTER TABLE telemetry_events DROP CONSTRAINT ck_telemetry_type;
ALTER TABLE telemetry_events ADD CONSTRAINT ck_telemetry_type CHECK (event_type IN
    ('ATTEMPT_SUBMITTED', 'BKT_UPDATED', 'BDT_UPDATED',
     'REVIEW_SCHEDULED', 'DECAY_APPLIED', 'SELF_DOUBT_FLAGGED',
     'SMART_MARK_COMPLETED', 'HUMAN_MARK_RECORDED'));

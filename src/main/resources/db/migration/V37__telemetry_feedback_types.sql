-- V37: telemetry type constraint catches up with the student Smart Mark
-- feedback actions (session-113).
--
-- The learner Smart Mark surface (b4e0b30) added the two consumption events
-- SMART_FEEDBACK_EXPLAINED and SMART_IMPROVEMENT_PLAN_VIEWED to
-- TelemetryEvent.Type and to the TelemetryService listeners — but no migration
-- extended the ck_telemetry_type CHECK constraint (last extended by V24).
-- With zero production usage until the session-113 E2E probe, every
-- "Explain my feedback" / "Improve my answer" call would have died on
-- DataIntegrityViolation even after the transactional fix (931eb73): the
-- listener's INSERT violates ck_telemetry_type and rolls back the whole
-- request. Caught by SmartFeedbackFlowIT on the CI verify lane (run 35597512615).

ALTER TABLE telemetry_events DROP CONSTRAINT ck_telemetry_type;
ALTER TABLE telemetry_events ADD CONSTRAINT ck_telemetry_type CHECK (event_type IN
    ('ATTEMPT_SUBMITTED', 'BKT_UPDATED', 'BDT_UPDATED', 'REVIEW_SCHEDULED', 'DECAY_APPLIED',
     'SELF_DOUBT_FLAGGED', 'SMART_MARK_COMPLETED', 'HUMAN_MARK_RECORDED', 'KA_RAG_COMPLETED',
     'STRUGGLE_INFERRED', 'TUTOR_INTERVENTION_SELECTED', 'CLA_EXCHANGE_COMPLETED',
     'SMART_FEEDBACK_EXPLAINED', 'SMART_IMPROVEMENT_PLAN_VIEWED'));

-- V45 — FK closure for learner-owned rows (deep-audit 2026-09-28, M6).
--
-- FINDING: eight learner-owned tables carried `learner_id UUID NOT NULL`
-- with no FOREIGN KEY to users(id). The service layer always writes learner
-- ids resolved from the JWT, so no application flow can create an orphan —
-- but the schema itself did not enforce the posture its own migration
-- headers claim. V42's header is explicit: "Deletion follows the learner
-- account (cascade), same as every learner-owned row." Without the
-- constraint, that deletion rule is prose, not physics: manual DB surgery
-- (of which this project's history has examples) could orphan rows, and a
-- future code path writing an unvalidated learner id would corrupt
-- per-learner state silently — misconception probabilities, review
-- schedules, tutor transcripts attributed to nobody.
--
-- SCOPE — all eight learner_id columns, not just the audit-named pair:
--   attempts (V3), skill_states / misconception_states / review_schedules
--   (V4), telemetry_events (V5), tutor_topic_engagements (V21),
--   learner_self_marks (V31), tutor_sessions (V42).
-- One defect class, one migration: fixing only attempts + tutor_sessions
-- would leave the same hole in six sibling tables and guarantee a
-- follow-up. All are learner-scoped data; ON DELETE CASCADE matches the
-- documented data-minimization posture for a minors' pilot (learner data
-- goes with the learner, including its analytic traces).
--
-- PRE-FLIGHT (prod, 2026-09-28, read-only probe): 0 orphaned rows across
-- all eight tables (attempts 166, skill_states 98, misconception_states 15,
-- review_schedules 97, telemetry_events 2986, tutor_topic_engagements 619,
-- learner_self_marks 5, tutor_sessions 9; users 214). No cleanup pass is
-- included because there is nothing to clean — if a future database DOES
-- carry orphans, this migration FAILS LOUDLY at deploy rather than
-- silently deleting them (fail-closed; orphans are a forensics event, not
-- garbage).
--
-- NAMING: Postgres-default <table>_<column>_fkey, matching
-- struggle_inferences_learner_id_fkey and intervention_run_learner_id_fkey
-- — the two learner_id tables that already did this correctly (V14, V26).
--
-- JPA NOTE: ddl-auto is validate (Flyway owns the schema); Hibernate
-- constraint validation does not exist for FKs, so no entity changes ride
-- along. Attempt.learnerId / TutorSession.learnerId are mapped as bare
-- UUID columns and stay that way.

ALTER TABLE attempts
    ADD CONSTRAINT attempts_learner_id_fkey
    FOREIGN KEY (learner_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE skill_states
    ADD CONSTRAINT skill_states_learner_id_fkey
    FOREIGN KEY (learner_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE misconception_states
    ADD CONSTRAINT misconception_states_learner_id_fkey
    FOREIGN KEY (learner_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE review_schedules
    ADD CONSTRAINT review_schedules_learner_id_fkey
    FOREIGN KEY (learner_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE telemetry_events
    ADD CONSTRAINT telemetry_events_learner_id_fkey
    FOREIGN KEY (learner_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE tutor_topic_engagements
    ADD CONSTRAINT tutor_topic_engagements_learner_id_fkey
    FOREIGN KEY (learner_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE learner_self_marks
    ADD CONSTRAINT learner_self_marks_learner_id_fkey
    FOREIGN KEY (learner_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE tutor_sessions
    ADD CONSTRAINT tutor_sessions_learner_id_fkey
    FOREIGN KEY (learner_id) REFERENCES users (id) ON DELETE CASCADE;

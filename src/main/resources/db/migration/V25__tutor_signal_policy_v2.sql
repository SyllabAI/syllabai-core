-- V25: tutor-signal policy v2 (sprint-2 §9 — structured Tutor signals).
--
-- The V23 signal vocabulary stays the backbone; this adds exactly two new
-- RECORD-TIME signal types, both derived deterministically from facts already
-- in the pipeline (never from LLM output):
--
--   PREREQUISITE_HELP       the tutor policy's deterministic intervention plan
--                           chose PREREQUISITE_REVIEW for the ask (the plan is
--                           a rule output over measured struggle/BDT state)
--   CLARIFICATION_REQUEST   the question asks to re-state/clarify a previous
--                           explanation (fixed phrase list: "clarify",
--                           "what do you mean", "say that again", ...) —
--                           checked AFTER doubt phrases (a confusion word is
--                           the stronger self-report) and BEFORE explanation
--                           command words ("what do you mean" must not be
--                           swallowed by the "what do" explanation pattern)
--
-- Signals deliberately NOT added as types (insufficient deterministic
-- evidence at record time — they stay DERIVED, computed by consumers over
-- multiple rows, never stored as row facts):
--   repeated explanation request  >= 2 EXPLANATION_REQUEST rows on the same
--                                   learner+topic inside the window
--   unresolved question           the refused flag (an ask that got no
--                                   grounded answer) — already a column
--   post-explanation engagement   an EXPLANATION_REQUEST followed by a later
--                                   engagement row on the same topic
--   repeated engagement           count of rows — derived at read time
--
-- classifier_version records WHICH deterministic policy produced the row:
-- legacy rows are 'tutor-signals/v1' (the V23 classifier), new rows carry the
-- version of the classifier that wrote them. Provenance on every row remains:
-- learner, topic anchor, occurred_at, evidence_count (grounding strength),
-- refused, answer_model, surface + context identity (the source-interaction
-- reference — raw chat text never reaches this table).

ALTER TABLE tutor_topic_engagements DROP CONSTRAINT ck_tte_signal;

ALTER TABLE tutor_topic_engagements
    ADD CONSTRAINT ck_tte_signal CHECK (signal_type IN (
            'TOPIC_ENGAGEMENT', 'EXPLANATION_REQUEST', 'DOUBT_SIGNAL',
            'MISCONCEPTION_RELATED', 'PREREQUISITE_HELP', 'CLARIFICATION_REQUEST'));

ALTER TABLE tutor_topic_engagements
    ADD COLUMN classifier_version VARCHAR(48) NOT NULL DEFAULT 'tutor-signals/v1';

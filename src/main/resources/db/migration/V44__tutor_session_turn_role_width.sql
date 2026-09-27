-- V44 — widen tutor_session_turns.role from VARCHAR(8) to VARCHAR(16).
--
-- Incident (2026-09-27, session-141): the word 'assistant' is NINE
-- characters; V42 declared the column VARCHAR(8) — one short. Every
-- session-anchored ask since the s140 deploy inserted the user turn
-- (4 chars, fits) and then failed the assistant turn with "value too long
-- for type character varying(8)", rolling back the whole append
-- transaction and 500-ing the ask AFTER the LLM answer had already been
-- generated. Learners saw "AI answers are temporarily unavailable" on
-- every tutor-chat ask while exam-question help asks (no session anchor)
-- kept working — which is why the failure looked like a provider outage.
--
-- The s140 TutorSessionServiceTest is mock-based, so no test ever inserted
-- a real 'assistant' row against the Flyway schema; TutorSessionStoreFlowIT
-- now pins this incident class in CI against a real Postgres.
--
-- Production was hotfixed at 2026-09-27 09:38 UTC with this exact
-- statement (evidence: work/s141_hotfix_evidence.json, session-141
-- worklog); this migration makes the change permanent for every
-- environment Flyway owns. Widening a varchar is a metadata-only change
-- in Postgres — no table rewrite, no index rebuild.

ALTER TABLE tutor_session_turns
    ALTER COLUMN role TYPE VARCHAR(16);

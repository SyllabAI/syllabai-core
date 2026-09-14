-- V23: structured signal types on tutor engagements (productization §3).
--
-- P7 shipped the minimum viable learner memory: one row per deterministically
-- matched topic per ask (TOPIC_ENGAGEMENT). This extends it with a
-- deterministic signal classification, one per row, computed at record time
-- from facts already in the pipeline (never from LLM output):
--
--   MISCONCEPTION_RELATED  the tutor policy intervened on an active BDT
--                          misconception on a matched topic (deterministic
--                          intervention plan)
--   DOUBT_SIGNAL           the question contains an explicit confusion phrase
--                          ("don't understand", "confused", "not sure", ...)
--   EXPLANATION_REQUEST    the question uses explanation command words
--                          ("explain", "why", "how", "what is", ...)
--   TOPIC_ENGAGEMENT       default — the topic-match fact alone
--
-- One classification per row, precedence-ordered (misconception > doubt >
-- explanation > default): consumers get a single unambiguous type. The
-- existing refused flag stays the "unresolved interaction" signal — refused
-- rows with matched topics are asks that got no grounded answer.
--
-- Boundaries unchanged: no raw chat text reaches learner state (the
-- classifier reads the question inside the event handler and records only the
-- type); no LLM-derived fact becomes learner state; anonymous previews write
-- nothing.

ALTER TABLE tutor_topic_engagements
    ADD COLUMN signal_type VARCHAR(24) NOT NULL DEFAULT 'TOPIC_ENGAGEMENT'
        CONSTRAINT ck_tte_signal CHECK (signal_type IN (
            'TOPIC_ENGAGEMENT', 'EXPLANATION_REQUEST', 'DOUBT_SIGNAL',
            'MISCONCEPTION_RELATED'));

CREATE INDEX ix_tte_learner_signal ON tutor_topic_engagements (learner_id, signal_type);

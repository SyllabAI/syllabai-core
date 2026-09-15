-- V24: Contextual Learning Assistant (CLA) step-1 runtime — interaction-evidence
-- provenance columns + research telemetry event type.
--
-- V24 is the next serial Flyway migration number (AGENT.md §1: inspect main,
-- allocate serially — V23 was the highest), NOT a mission tranche label.
--
-- This implements the learner-memory half of the CLA contract
-- (docs/CONTEXTUAL_LEARNING_ASSISTANT_IMPLEMENTATION.md §6) for sequencing
-- step 1 (§10.1): every CLA exchange appends the SAME provenance-bearing
-- interaction-evidence rows as the free Tutor (deterministic topic anchors,
-- grounding strength, refusal flag, answering-model identity, deterministic
-- signal classification), distinguished by surface and carrying the resolved
-- context identity:
--
--   surface           FREE_TUTOR (legacy default) | CONTEXTUAL_ASSISTANT
--   response_mode     EXPLAIN | SUMMARIZE (contract §3; null on tutor rows)
--   context_kind      KG_TOPIC in step 1 (contract §1 closed enum)
--   context_reference the resolved topic node id (canonical anchor)
--
-- Boundaries unchanged (AGENT.md §10): no raw chat text in learner memory;
-- deterministic signal classification only; append-only table; anonymous
-- previews write nothing. Existing rows keep surface='FREE_TUTOR' semantics
-- exactly — the default backfills them honestly, no data rewrite.
--
-- Consumer note (LIM extension rules §4.4): existing read-only, windowed
-- consumers (LearnerStateController signalCounts, Smart Lesson advance leg,
-- NBA T7a) now see CONTEXTUAL_ASSISTANT engagement rows alongside FREE_TUTOR
-- rows. This is intended: a CLA doubt signal is the same learner-memory fact
-- class as a tutor doubt signal, and the surface column is present so any
-- consumer that must distinguish them can do so without another migration.

ALTER TABLE tutor_topic_engagements
    ADD COLUMN surface VARCHAR(24) NOT NULL DEFAULT 'FREE_TUTOR'
        CONSTRAINT ck_tte_surface CHECK (surface IN ('FREE_TUTOR', 'CONTEXTUAL_ASSISTANT')),
    ADD COLUMN response_mode VARCHAR(20),
    ADD COLUMN context_kind VARCHAR(32),
    ADD COLUMN context_reference UUID;

CREATE INDEX ix_tte_learner_surface ON tutor_topic_engagements (learner_id, surface, occurred_at DESC);

-- Research telemetry (audit/history layer): one CLA_EXCHANGE_COMPLETED row per
-- CLA exchange — carries the question text (research telemetry ONLY, never
-- learner memory) plus the read-only tool invocation trace (tool, arguments
-- reference, result size, latency) required by the CLA contract §4.4.
ALTER TABLE telemetry_events DROP CONSTRAINT ck_telemetry_type;
ALTER TABLE telemetry_events ADD CONSTRAINT ck_telemetry_type CHECK (event_type IN
    ('ATTEMPT_SUBMITTED', 'BKT_UPDATED', 'BDT_UPDATED', 'REVIEW_SCHEDULED', 'DECAY_APPLIED',
     'SELF_DOUBT_FLAGGED', 'SMART_MARK_COMPLETED', 'HUMAN_MARK_RECORDED', 'KA_RAG_COMPLETED',
     'STRUGGLE_INFERRED', 'TUTOR_INTERVENTION_SELECTED', 'CLA_EXCHANGE_COMPLETED'));

-- §19 registry: the CLA pipeline registers its model version. The generation
-- prompt is tutor-grounded/v2 REUSED verbatim (contract: compose the Tutor's
-- generation + citation-validation stack — the prompt registry row already
-- exists; duplicating the template would create drift risk, so none is added).
INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('73000000-0000-0000-0000-000000000003', 'cla-contextual', '1.0.0',
     '{"stages": ["resource-context-resolution", "bounded-read-only-tools", "deterministic-anchors", "kg+vector-retrieval", "rrf-fusion", "grounding-gate", "mode-constrained-grounded-generation", "citation-resolution", "interaction-evidence", "telemetry"], "contextKind": "KG_TOPIC", "modes": ["EXPLAIN", "SUMMARIZE"], "tools": ["GET_SPECIFICATION_CONTEXT", "GET_RELATED_CONCEPTS", "GET_LEARNER_STATE"], "toolPolicy": "server-owned registry, fixed deterministic composition, read-only, invocation-traced", "prompt": "tutor-grounded/v2 (reused verbatim)", "refusal": "deterministic-on-empty-evidence", "anchors": "server-resolved VALIDATED KG topic, never model-invented"}',
     'syllabai-core V24; CLA contract §10 step 1 (docs/CONTEXTUAL_LEARNING_ASSISTANT_IMPLEMENTATION.md)',
     'CLA contextual pipeline v1.0.0: server-resolved KG_TOPIC ResourceContext (fail-closed, subject-isolated, VALIDATED-only), bounded read-only tool composition, deterministic topic anchors, hybrid retrieval with the context anchor as authoritative prior, EXPLAIN/SUMMARIZE modes, interaction evidence into LIM with surface=CONTEXTUAL_ASSISTANT',
     now());

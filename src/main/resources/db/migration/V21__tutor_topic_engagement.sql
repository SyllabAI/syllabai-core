-- V21: tutor topic engagement — the minimum viable learner-memory pipeline from
-- Tutor chats (P7, Master Spec §13 learning log + §11 learner state).
--
-- Design rule (operator directive): ONLY structured, provenance-bearing
-- evidence reaches learner state — the deterministic intent matcher's topic
-- IDs, the grounding strength and the answering model identity. The raw chat
-- text NEVER mutates learner state; it lives only in the immutable research
-- telemetry log (telemetry_events, KA_RAG_COMPLETED). No LLM output is stored
-- here: matched topics come from the deterministic matcher, so this table can
-- never inherit a hallucination.

CREATE TABLE tutor_topic_engagements (
    id             UUID PRIMARY KEY,
    learner_id     UUID NOT NULL,
    node_id        UUID NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL,
    evidence_count INT NOT NULL,
    refused        BOOLEAN NOT NULL,
    answer_model   VARCHAR(120),
    created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_tte_learner_recent ON tutor_topic_engagements (learner_id, occurred_at DESC);
CREATE INDEX ix_tte_node ON tutor_topic_engagements (node_id);

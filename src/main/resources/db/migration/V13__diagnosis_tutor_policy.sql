-- V13: explainable diagnosis and diagnosis-aware tutor policy (T-026/T-027).
CREATE TABLE struggle_inferences (
    id UUID PRIMARY KEY,
    learner_id UUID NOT NULL REFERENCES users(id),
    topic_node_id UUID NOT NULL REFERENCES knowledge_nodes(id),
    type VARCHAR(40) NOT NULL,
    subtype VARCHAR(80) NOT NULL,
    probability DOUBLE PRECISION NOT NULL CHECK (probability >= 0 AND probability <= 1),
    supporting_evidence JSONB NOT NULL,
    model_version VARCHAR(80) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    teacher_override VARCHAR(80),
    overridden_by UUID REFERENCES users(id),
    overridden_at TIMESTAMPTZ,
    CHECK (expires_at > generated_at)
);
CREATE INDEX idx_struggle_inference_learner_topic_expiry
    ON struggle_inferences (learner_id, topic_node_id, expires_at DESC, probability DESC);
CREATE INDEX idx_struggle_inference_learner_expiry
    ON struggle_inferences (learner_id, expires_at DESC, probability DESC);

ALTER TABLE telemetry_events DROP CONSTRAINT ck_telemetry_type;
ALTER TABLE telemetry_events ADD CONSTRAINT ck_telemetry_type CHECK (event_type IN
    ('ATTEMPT_SUBMITTED', 'BKT_UPDATED', 'BDT_UPDATED', 'REVIEW_SCHEDULED',
     'DECAY_APPLIED', 'SELF_DOUBT_FLAGGED', 'SMART_MARK_COMPLETED',
     'HUMAN_MARK_RECORDED', 'KA_RAG_COMPLETED', 'STRUGGLE_INFERRED',
     'TUTOR_INTERVENTION_SELECTED'));

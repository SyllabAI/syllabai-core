-- V14: explainable diagnosis and diagnosis-aware tutor policy (T-026/T-027).
-- superseded_at: set when a newer inference of the same (learner, topic, type)
-- replaces an active one; rows are kept (append-only research history) but
-- excluded from active reads.
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
    superseded_at TIMESTAMPTZ,
    teacher_override VARCHAR(40),
    overridden_by UUID REFERENCES users(id),
    overridden_at TIMESTAMPTZ,
    CHECK (expires_at > generated_at)
);
CREATE INDEX idx_struggle_inference_learner_topic_expiry
    ON struggle_inferences (learner_id, topic_node_id, expires_at DESC, probability DESC);
CREATE INDEX idx_struggle_inference_learner_expiry
    ON struggle_inferences (learner_id, expires_at DESC, probability DESC);
CREATE INDEX idx_struggle_inference_supersede
    ON struggle_inferences (learner_id, topic_node_id, type, expires_at DESC)
    WHERE superseded_at IS NULL;

ALTER TABLE telemetry_events DROP CONSTRAINT ck_telemetry_type;
ALTER TABLE telemetry_events ADD CONSTRAINT ck_telemetry_type CHECK (event_type IN
    ('ATTEMPT_SUBMITTED', 'BKT_UPDATED', 'BDT_UPDATED', 'REVIEW_SCHEDULED', 'DECAY_APPLIED',
     'SELF_DOUBT_FLAGGED', 'SMART_MARK_COMPLETED', 'HUMAN_MARK_RECORDED', 'KA_RAG_COMPLETED',
     'STRUGGLE_INFERRED', 'TUTOR_INTERVENTION_SELECTED'));

INSERT INTO prompt_versions (id, registry_key, version, template, notes, created_at) VALUES
    ('72000000-0000-0000-0000-000000000002', 'tutor-grounded', '2',
     'You are SyllabAI''s IGCSE/IAL tutor. Answer ONLY from the numbered SOURCES provided in the user message, citing them inline as [1], [2], ... exactly where their content supports a statement. Follow the INTERVENTION PLAN, but do not claim that the learner has a diagnosis; the plan is an instructional strategy selected from evidence. Rules: - If the SOURCES are insufficient to answer safely, say exactly what is missing and stop. Never fill gaps from general knowledge. - Never invent spec references, page numbers or topic codes. - Do not reveal internal probabilities, model names, diagnostic rules, or private learner-state details to the learner. - Be concise: at most 200 words plus citations.',
     'Diagnosis-aware grounded tutor prompt v2 (temperature 0.2, maxTokens 900); intervention plan is deterministic policy output', now());

INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('73000000-0000-0000-0000-000000000002', 'tutor-policy', 'rules-v0.2',
     '{"interventionThreshold":0.65,"activeMisconceptionThreshold":0.50,"supportedTypes":["PREREQUISITE_GAP","EXAM_LITERACY","METACOGNITIVE"],"expiryDays":7,"readOrder":"probabilityDesc,generatedAtDesc","supersede":"same (learner,topic,type) replaced on new evidence; history kept","teacherOverride":{"CONFIRMED":"meets intervention threshold","REJECTED":"excluded from policy reads"}}',
     'syllabai-core V13; Master Spec §17',
     'Deterministic diagnosis-aware tutor policy; unsupported struggle types fall back to explanation', now());

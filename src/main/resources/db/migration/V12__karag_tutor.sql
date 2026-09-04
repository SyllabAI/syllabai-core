-- V12: KA-RAG telemetry type + research registries (T-024, Master Spec §13/§18/§19).
-- Documented additive extension, mirroring V9's pattern: Cycle-1 event types are
-- unchanged, and the tutor chat-exchange record (Paper B §3.5) lands as one new
-- append-only event type covering both answered and refused interactions:
--   KA_RAG_COMPLETED — every /tutor/ask outcome (refusal included)
-- Registries per §19: every LLM touchpoint gets a registered prompt + model
-- version. The tutor-grounded prompt template below is byte-identical to
-- GroundedTutorGenerator.PROMPT_REGISTRY_KEY v1 in code — drift between the
-- seed and the constant fails the KaRagFlowIT registry check.

ALTER TABLE telemetry_events DROP CONSTRAINT ck_telemetry_type;
ALTER TABLE telemetry_events ADD CONSTRAINT ck_telemetry_type CHECK (event_type IN
    ('ATTEMPT_SUBMITTED', 'BKT_UPDATED', 'BDT_UPDATED',
     'REVIEW_SCHEDULED', 'DECAY_APPLIED', 'SELF_DOUBT_FLAGGED',
     'SMART_MARK_COMPLETED', 'HUMAN_MARK_RECORDED', 'KA_RAG_COMPLETED'));

INSERT INTO prompt_versions (id, registry_key, version, template, notes, created_at) VALUES
    ('72000000-0000-0000-0000-000000000001', 'tutor-grounded', '1',
     'You are SyllabAI''s IGCSE/IAL tutor. Answer ONLY from the numbered SOURCES provided in the user message, citing them inline as [1], [2], ... exactly where their content supports a statement. Rules: - If the SOURCES are insufficient to answer safely, say exactly what is missing and stop. Never fill gaps from general knowledge. - Never invent spec references, page numbers or topic codes. - Address the learner brief when present (mastery, misconceptions) by choosing language the learner can follow, but do not psychoanalyse. - Be concise: at most 200 words plus the citations.',
     'Grounded tutor generation prompt v1 (temperature 0.2, maxTokens 900, evidence block capped); refusal path is deterministic (no LLM call)',
     now());

INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('73000000-0000-0000-0000-000000000001', 'ka-rag-pipeline', '1.0.0',
     '{"stages": ["intent", "kg-retrieval", "vector-retrieval", "rrf-fusion", "rerank", "context-assembly", "grounded-generation", "citation-resolution", "telemetry"], "intent": "deterministic-token-match", "fusion": "reciprocal-rank-fusion k=60", "reranker": "no-rerank (identity)", "evidenceLimit": 6, "refusal": "deterministic-on-empty-evidence"}',
     'syllabai-core V12; Master Spec §13',
     'KA-RAG pipeline v1.0.0: hybrid KG+vector retrieval with rank fusion, grounded generation via the free-LLM chain, citation contract, KA_RAG_COMPLETED telemetry',
     now());

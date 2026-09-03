-- V10: research registry seeds for the Smart Mark pipeline (Master Spec §19
-- reproducibility — every LLM touchpoint has a registered prompt + model version).

INSERT INTO prompt_versions (id, registry_key, version, template, notes, created_at) VALUES
    ('70000000-0000-0000-0000-000000000001', 'smart-mark-candidate', '1',
     'You are an exam marker aligned strictly to the provided mark scheme. For every MARK POINT decide exactly one allocation: awarded (true only when the learner''s answer contains the point''s required content), evidence (shortest verbatim quote), rationale (one sentence). Respond with ONLY a JSON object: {"confidence": <0..1>, "allocations": [{"markPointId": "<id>", "ref": "<ref>", "awarded": true|false, "evidence": "...", "rationale": "..."}]}. Decide EVERY listed mark point. Never invent mark point ids.',
     'Smart Mark candidate generation prompt v1 (temperature 0.1, maxTokens 800); validated downstream by bounds/coverage/mark-sum validators', now());

INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('71000000-0000-0000-0000-000000000001', 'smart-mark-pipeline', '1.0.0',
     '{"stages": ["normalize", "decompose", "generate", "validate", "persist"], "validators": ["bounds", "coverage", "mark-sum"], "authoritative": "human-or-kappa-gate", "kappaThreshold": 0.60}',
     'syllabai-core V8/V10; Master Spec §15',
     'Smart Mark pipeline v1.0.0: candidate generation via free-LLM chain, deterministic validation gates, append-only results, κ >= 0.60 release gate', now());

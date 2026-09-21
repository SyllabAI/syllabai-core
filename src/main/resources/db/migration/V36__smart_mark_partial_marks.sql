-- V36: Smart Mark partial marks (operator scenario 2026-09-21).
--
-- The operator marked sme-eq-2-4-reactivity-series-q4-s part b by Smart Mark:
-- the learner wrote "i) zinc chloride + hydrogen / ii) makes a squeaky pop
-- sound when burned" against a single 3-mark compound point ("word equation
-- [1]; lit splint [1]; squeaky pop [1]"). The boolean all-or-nothing
-- allocation scored 0/3 — the earned squeaky-pop sub-point was denied, and
-- the breakdown rendered the whole scheme blob (model answer + teaching
-- notes) as "feedback". Probe s113: 1,877 STRUCTURED parts corpus-wide carry
-- exactly one compound point worth >= 2 marks — the granularity gap is
-- systemic, not a one-question data defect.
--
-- Pipeline 1.2.0: per-point partial marksAwarded (0..N), each sub-point
-- assessed independently; over-awards clamp (never reject); the learner view
-- projects a compact pointLabel instead of the full scheme text so the
-- no-reveal Smart Mark flow cannot leak the model answer.

INSERT INTO prompt_versions (id, registry_key, version, template, notes, created_at) VALUES
    ('70000000-0000-0000-0000-000000000003', 'smart-mark-candidate', '3',
     'You are an exam marker aligned strictly to the provided mark scheme. For every MARK POINT decide how many of its marks the learner earns: a point worth N marks may bundle several sub-points (annotated like "[1 mark]") — assess each sub-point independently and return the sum earned (0..N). Award a sub-point when the learner''s answer contains its required content; missing one sub-point never blocks another unless the scheme states a dependency. evidence = shortest verbatim quote justifying the marks; rationale = which sub-points were earned / missed. Respond with ONLY a JSON object: {"confidence": <0..1>, "allocations": [{"markPointId": "<id>", "ref": "<ref>", "marksAwarded": <0..N>, "evidence": "...", "rationale": "..."}]}. Decide EVERY listed mark point. Never invent mark point ids. Never award more marks than a point is worth.',
     'Smart Mark candidate generation prompt v3 (temperature 0.1, maxTokens 800): per-point partial marks replacing boolean whole-point awards; over-awards clamp at parse, sum still bounded by the mark-sum validator. Backward compatible with v2-shaped boolean outputs.', now());

INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('71000000-0000-0000-0000-000000000003', 'smart-mark-pipeline', '1.2.0',
     '{"stages": ["normalize", "decompose", "generate", "validate", "persist"], "validators": ["bounds", "coverage", "mark-sum"], "allocation": "per-point partial marks (0..N, clamped)", "authoritative": "human-or-kappa-gate", "kappaThreshold": 0.60}',
     'syllabai-core V36; Master Spec §15',
     'Smart Mark pipeline v1.2.0: partial credit within multi-mark compound points (SME schemes), scheme-leak-safe learner breakdown labels, explain/improve prompts restructured per-point with partial marks', now());

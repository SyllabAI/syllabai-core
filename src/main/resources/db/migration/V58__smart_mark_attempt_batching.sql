-- V58: Smart Mark single-call attempt batching (latency round 2026-10-02).
--
-- The learner "Smart mark attempt" pass was K serial reasoning-model calls
-- under ONE @Transactional holding the attempt row lock (one markAnswer per
-- part; the deployed groq openai/gpt-oss-120b candidate call costs seconds on
-- its own). A 4-part attempt paid 4 reasoning round trips + 4 κ-gate pairs +
-- 4 version/scheme loads inside one lock window.
--
-- Pipeline 1.3.0 (prompt v4): the pass marks every answered part of the
-- attempt in ONE provider call — same marking rules and per-point partial-marks
-- semantics as v3, only the transport envelope changes ({"parts": [...]} keyed
-- by 1-based part number). A truncated/unparseable batch refuses as a whole and
-- the pipeline falls back to the classic per-part topology, so the fast path
-- can never lower marking availability. Teacher-lane marking stays per-part.
--
-- Calibration honesty: batch rows keep the FULL batch raw output (the row shows
-- the whole response it came from) and ride the same validators/κ pairing; the
-- pipeline version 1.3.0 distinguishes them in the run log.

INSERT INTO prompt_versions (id, registry_key, version, template, notes, created_at) VALUES
    ('70000000-0000-0000-0000-000000000004', 'smart-mark-candidate', '4',
     'You are an exam marker aligned strictly to the provided mark scheme. The learner answered SEVERAL question parts of one question — mark each part INDEPENDENTLY against its own listed mark points; an answer to one part never earns another part''s marks. For every MARK POINT decide how many of its marks the learner earns: a point worth N marks may bundle several sub-points (annotated like "[1 mark]") — assess each sub-point independently and return the sum earned (0..N). evidence = shortest verbatim quote justifying the marks; rationale = which sub-points were earned / missed. Respond with ONLY a JSON object: {"parts": [{"part": <partNumber>, "confidence": <0..1>, "allocations": [{"markPointId": "<id>", "ref": "<ref>", "marksAwarded": <0..N>, "evidence": "...", "rationale": "..."}]}]} where <partNumber> is the 1-based number printed in the PART header. Return one object for EVERY listed part. Decide EVERY listed mark point of every part. Never invent mark point ids. Never award more marks than a point is worth.',
     'Smart Mark candidate generation prompt v4 (attempt-batch topology): one call marks all of an attempt''s answered parts (temperature 0.1, maxTokens 800 + 400/total in-scope mark, capped 8000). Marking rules byte-identical to v3; only the transport envelope changes (parts array keyed by 1-based part number). Whole-batch refusal on truncation/parse failure feeds the pipeline''s per-part fallback ladder.', now());

INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('71000000-0000-0000-0000-000000000004', 'smart-mark-pipeline', '1.3.0',
     '{"stages": ["normalize", "decompose", "generate", "validate", "persist"], "validators": ["bounds", "coverage", "mark-sum"], "generation_topology": "attempt-batch (single call) with per-part fallback ladder", "allocation": "per-point partial marks (0..N, clamped)", "authoritative": "human-or-kappa-gate", "kappaThreshold": 0.60}',
     'syllabai-core V58; Master Spec §15',
     'Smart Mark pipeline v1.3.0: the learner pass marks every part of an attempt with one candidate-generation call (prompt v4); per-point decision semantics, deterministic validators and κ pairing unchanged from the 1.2.x line; teacher-lane marking stays per-part', now());

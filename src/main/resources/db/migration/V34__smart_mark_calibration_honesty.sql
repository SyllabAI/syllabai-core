-- V34: Smart Mark pre-calibration honesty (audit 2026-09-20, gaps G-2 + G-3).
-- Both changes exist so the FIRST κ calibration run (session-96 double-marking
-- sample) is spent against the marking surface the pipeline will actually use,
-- never against a surface that changes after the pilot's effort is sunk.
--
-- G-2 — scheme validation-state honesty:
--   Smart Mark must mark only VALIDATED schemes (SUGGESTED / REJECTED / FLAGGED
--   never back marking — the FLAGGED rule was stated in V20's entity contract
--   but the marking-path selection never enforced any state). Two provenance
--   columns on the append-only run log make every calibration row segmentable
--   by what it was actually marked against, as-of the run:
--     mark_scheme_id          the scheme the run selected
--     scheme_validation_state that scheme's state AS OF the run
--
-- G-3 — scheme-level general guidance:
--   Edexcel mark schemes carry scheme-level instructions ("accept ecf",
--   "ignore significant figure penalties", "allow reverse ordering") that have
--   no per-point home. mark_schemes.general_guidance gives them one; the
--   marking prompt (registry v2) renders the section when the scheme carries it.

-- ── G-2: provenance of what each run was marked against ────────────────────
ALTER TABLE smart_mark_results ADD COLUMN mark_scheme_id UUID
    REFERENCES mark_schemes (id);
ALTER TABLE smart_mark_results ADD COLUMN scheme_validation_state VARCHAR(12);

-- ── G-3: scheme-level general instructions (ecf / ignore rules) ────────────
ALTER TABLE mark_schemes ADD COLUMN general_guidance TEXT;

-- ── §19 reproducibility registry: prompt v2 + pipeline v1.1.0 ──────────────
INSERT INTO prompt_versions (id, registry_key, version, template, notes, created_at) VALUES
    ('70000000-0000-0000-0000-000000000002', 'smart-mark-candidate', '2',
     'You are an exam marker aligned strictly to the provided mark scheme. For every MARK POINT decide exactly one allocation: awarded (true only when the learner''s answer contains the point''s required content), evidence (shortest verbatim quote), rationale (one sentence). Respond with ONLY a JSON object: {"confidence": <0..1>, "allocations": [{"markPointId": "<id>", "ref": "<ref>", "awarded": true|false, "evidence": "...", "rationale": "..."}]}. Decide EVERY listed mark point. Never invent mark point ids. When SCHEME-LEVEL GENERAL INSTRUCTIONS are provided (e.g. accept ecf, ignore significant-figure penalties), apply them to every mark point decision.',
     'v2: adds the scheme-level general-guidance section (rendered only when the scheme carries it); v1 remains valid for schemes without guidance. Temperature 0.1, maxTokens 800 unchanged; validators unchanged', now());

INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('71000000-0000-0000-0000-000000000002', 'smart-mark-pipeline', '1.1.0',
     '{"stages": ["normalize", "decompose", "generate", "validate", "persist"], "validators": ["bounds", "coverage", "mark-sum"], "authoritative": "human-or-kappa-gate", "kappaThreshold": 0.60, "promptVersion": 2, "validatedSchemeOnly": true, "resultProvenance": ["mark_scheme_id", "scheme_validation_state"]}',
     'syllabai-core V34; Smart Mark pre-calibration honesty (G-2/G-3)',
     'Smart Mark pipeline v1.1.0: marks only VALIDATED schemes (non-validated selection persists an honest SCHEME_NOT_VALIDATED refusal row), prompt v2 with optional scheme-level general guidance, per-row scheme provenance', now());

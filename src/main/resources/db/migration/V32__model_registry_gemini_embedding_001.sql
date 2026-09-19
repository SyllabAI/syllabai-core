-- V32 — §19 reproducibility: register content-embedding v1.1.0 (gemini-embedding-001).
--
-- V11 seeded this registry with v1.0.0 (text-embedding-004), which Gemini has since
-- RETIRED (the API returns 404 on both v1 and v1beta; probed with a valid key on
-- 2026-09-17). The GA successor gemini-embedding-001 honours outputDimensionality=768,
-- so the V11 vector(768) column contract and every stored embedding remain valid.
-- Production already received this row out-of-band when the 2,333/2,333 chunk corpus
-- was backfilled from the frozen multi-key artifact (2026-09-18), so this seed is
-- conflict-guarded: it registers the successor on fresh databases (CI, rebuilt
-- environments) and is a no-op where v1.1.0 already exists. Append-only per §19 —
-- v1.0.0 stays as history, never rewritten; readers take latest-wins per registry_key.
INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('70000000-0000-0000-0000-000000000011', 'content-embedding', '1.1.0',
     '{"provider":"gemini","model":"gemini-embedding-001","dimension":768,
       "documentTaskType":"RETRIEVAL_DOCUMENT","queryTaskType":"RETRIEVAL_QUERY",
       "failover":"none-by-design","batching":"spring-ai-default"}',
     'text-embedding-004 retired (404 v1+v1beta, probed 2026-09-17); successor verified at outputDimensionality=768; 2,333/2,333 production chunks backfilled from frozen artifact 2026-09-18',
     'Successor of v1.0.0 (retired text-embedding-004). No failover: a mixed-model index would be inconsistent, so an unavailable provider fails loudly instead of degrading silently.',
     now())
ON CONFLICT ON CONSTRAINT uq_model_version DO NOTHING;

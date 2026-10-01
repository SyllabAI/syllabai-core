-- V56 (T-C40 ③a, 2026-10-01): persist the production-critical pgvector HNSW
-- filtered-scan settings in a migration.
--
-- WHY THIS EXISTS (review R6, RAG_ENGINE_REVIEW_2026-10-01.md): the 2026-09-28
-- rev2 re-stamp cut-over exposed a pre-existing HNSW post-filter mechanism —
-- with iterative_scan=off, the filtered ANN traversal under-delivers when
-- SUGGESTED-dense neighbourhoods exhaust the candidate budget (rejected
-- candidates are not replaced; live probe fell 50/50 -> 46/50, worst-case
-- DB probe 0 -> 10 rows). The remedy was applied BY HAND
-- (REINDEX + ALTER DATABASE neondb SET hnsw.iterative_scan = 'strict_order'
-- + one pooled-backend recycle; final probe 50/50, top-1 scores bit-identical
-- — evidence/serving-rev2-restamp-cutover-2026-09-28/hnsw_incident.json), but
-- the setting appeared in NO migration, NO render.yaml, NO start.sh, NO
-- docker-compose.yml. A fresh Neon branch, a restore, or any new environment
-- silently reverted to the under-delivery behaviour — the exact incident
-- class already lived through, one lucky query away from invisible.
--
-- WHAT: database-level pin of both settings. iterative_scan = 'strict_order'
-- is the pgvector-documented filtered-scan fix (iterative index scans:
-- revisit the index until enough candidates pass the filter, in strict index
-- order). ef_search = 40 is the pgvector DEFAULT and today's de-facto
-- production value — pinned EXPLICITLY so the untuned-but-current behaviour
-- is recorded; tuning it is a separately benchmarked decision (T-C13 gate),
-- never a silent default shift.
--
-- MECHANICS: ALTER DATABASE ... SET applies to NEW connections only; existing
-- pooled backends keep their session settings. That is safe here: Flyway runs
-- before the pool warms on every deploy/start (Render restarts the service
-- after migrate), so the serving pool picks the settings up immediately. For
-- a long-lived foreign pool, recycle it or SET the GUCs per-session — do not
-- re-run this migration.
--
-- PORTABILITY: the database name is NOT hardcoded (neondb vs syllabai_test vs
-- a Testcontainers db) — current_database() resolves it at execution time.
-- The hnsw.* GUCs exist wherever the pgvector extension is installed (V11
-- creates it, so every database that reaches V54 has them).

DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET hnsw.iterative_scan = %L',
                   current_database(), 'strict_order');
    EXECUTE format('ALTER DATABASE %I SET hnsw.ef_search = %L',
                   current_database(), '40');
END
$$;

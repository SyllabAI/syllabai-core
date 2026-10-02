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
--
-- ---------------------------------------------------------------------------
-- ADDENDUM (T-C48, 2026-10-02): production deploy unblock — the strict form
-- below was UNRUNNABLE under the Neon app role and blocked every core deploy
-- since it landed. First failure signature (Render logs, deploy
-- dep-davl893tqb8s73ffq090, 2026-10-02T06:52:50Z, all five deploys that day
-- identical):
--   ERROR: permission denied to set parameter "hnsw.iterative_scan"
--   Where: SQL statement "ALTER DATABASE neondb SET hnsw.iterative_scan = ..."
--          PL/pgSQL function inline_code_block line 3 at EXECUTE
-- ALTER DATABASE ... SET on a pgvector custom GUC is an elevated-privilege
-- operation (superuser / database owner with the GUC defined); the serving
-- role is neither, and correctly so. The production DB already carries the
-- DB-level pin from the hand-applied 2026-09-28 remedy — verified first-hand
-- 2026-10-02: current_setting('hnsw.iterative_scan') = 'strict_order'.
--
-- Tolerant semantics (fail-closed preserved, never silent):
--   1. TRY the privileged pin — environments whose role CAN alter the
--      database (fresh installs, Testcontainers, owner roles) keep the full
--      behaviour, byte-for-byte the same statements as before.
--   2. On insufficient_privilege, accept the skip ONLY when the DB-level pin
--      is already in place (iterative_scan = 'strict_order'; ef_search either
--      unset — NULL, i.e. the pgvector default 40 the pin records — or
--      explicitly '40'). Production hits exactly this branch.
--   3. Anything else re-raises: a fresh restricted-role environment starts
--      LOUDLY ABORTED, never silently unpinned — the original contract.
--
-- Checksum-change safety: V56 never reached a successful apply in any
-- persistent environment (production flyway_schema_history has no V56 row —
-- verified 2026-10-02; CI containers are ephemeral), so editing the script
-- breaks no recorded checksum.

DO $$
DECLARE
    v_scan text;
    v_ef   text;
BEGIN
    BEGIN
        EXECUTE format('ALTER DATABASE %I SET hnsw.iterative_scan = %L',
                       current_database(), 'strict_order');
        EXECUTE format('ALTER DATABASE %I SET hnsw.ef_search = %L',
                       current_database(), '40');
    EXCEPTION
        WHEN insufficient_privilege THEN
            v_scan := current_setting('hnsw.iterative_scan', true);
            v_ef   := current_setting('hnsw.ef_search', true);
            IF v_scan IS DISTINCT FROM 'strict_order' THEN
                RAISE EXCEPTION 'V56: cannot ALTER DATABASE SET hnsw.iterative_scan (insufficient privilege) and the DB-level pin is absent or different (got %) — apply the pin with an elevated role, then redeploy',
                    coalesce(v_scan, '<unset>');
            END IF;
            IF v_ef IS NOT NULL AND v_ef <> '40' THEN
                RAISE EXCEPTION 'V56: cannot ALTER DATABASE SET hnsw.ef_search (insufficient privilege) and the DB-level value is not the pinned default (got %)', v_ef;
            END IF;
            RAISE NOTICE 'V56: hnsw.iterative_scan already pinned at DB level (elevated hand-apply 2026-09-28); skipping the privileged re-pin (app role lacks ALTER DATABASE SET). ef_search DB-level entry absent = pgvector default 40, which is the pinned value.';
    END;
END
$$;

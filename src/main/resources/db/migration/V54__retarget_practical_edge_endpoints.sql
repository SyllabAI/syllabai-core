-- V54: retarget the validated practical REQUIRES_PREREQUISITE edges from the
-- ad-hoc practical-node codes (4CH1-PR-01..12) to their real core-practical
-- spec statements — the upstream lockstep of the syllabai-hub mirror retarget
-- (fix_pr_edges.py fd05d75 + fix_pr_mirror.py; hub manifest
-- docs/TC11_BATCH5_MANIFEST.md follow-up 1, operator trace 1a0f5be571fa8f21).
-- First applied as V47 against the batch-4 store (12 edges); renumbered and
-- re-scoped after the batch-11 core sync (96e7bec) landed the operator's
-- 7-edge package (PR-05/06/07/08 x1 + PR-12 x3, sourced at their real spec
-- statements by scripts/retarget_pr_endpoints.py alongside the original 12).
--
-- WHY A DATA MIGRATION: the concept-graph seed (V15 ConceptGraphSeedService) is
-- idempotent-by-identity and never deletes or updates — a snapshot-only
-- retarget would leave the 12 ad-hoc rows stranded in this table while a
-- re-activation created 12 new ones (duplicated educational relations). This
-- migration moves the seeded rows IN PLACE, touching only source_node_id:
-- provenance, rationale, validation_status, strength and created_by are
-- byte-preserved, so a re-activation resolves each migrated row as a reused
-- row under the same (source, target, relation) identity (uq_edge) and the
-- same seed provenance line (requireSeedEdge contract) — the store snapshot
-- keeps those edges' provenance fields byte-identical for exactly this reason.
--
-- NO-OP CONTRACT: on a database where the 4CH1 concept-graph seed has not run
-- (fresh environments, CI, any pre-V15 database) there is nothing to retarget
-- and this migration is a structural no-op. On a seeded database the update
-- must move EXACTLY 12 rows — the batch-4-era store seeded exactly that many
-- ad-hoc-sourced rows, and the 7 batch-5/6/7/11 edges were never seeded
-- anywhere (they enter the KG through re-activation with the new snapshot,
-- already at their real spec statements). Any other count is store drift and
-- fails the boot loudly (fail-closed; resolve manually, never guess).
--
-- DELIBERATELY UNTOUCHED: the practical NODES (4CH1-PR-xx, official spec
-- content) and their PART_OF anchor edges to their spec points remain — the
-- practicals stay first-class in the curriculum tree; only the SOURCE of the
-- 12 validated prerequisite edges moves to the spec statement.

DO $$
DECLARE
    updated INT := 0;
BEGIN
    -- fresh / never-seeded database: nothing to retarget
    IF NOT EXISTS (
        SELECT 1 FROM knowledge_edges e
        JOIN knowledge_nodes s ON s.id = e.source_node_id
        WHERE e.relation_type = 'REQUIRES_PREREQUISITE'
          AND s.code IN ('4CH1-PR-01', '4CH1-PR-02', '4CH1-PR-03', '4CH1-PR-04',
                         '4CH1-PR-09', '4CH1-PR-10', '4CH1-PR-11')
    ) THEN
        RETURN;
    END IF;

    -- every target spec-statement node must exist before any row moves
    IF (SELECT COUNT(*) FROM knowledge_nodes WHERE code IN
            ('4CH1-1.7C', '4CH1-1.13', '4CH1-1.36', '4CH1-1.60C',
             '4CH1-3.8', '4CH1-3.15', '4CH1-3.16')) <> 7 THEN
        RAISE EXCEPTION 'V54 retarget: target spec-statement node(s) missing — seed drift, resolve manually';
    END IF;

    WITH mapping(pr_code, sp_code) AS (VALUES
        ('4CH1-PR-01', '4CH1-1.7C'),
        ('4CH1-PR-02', '4CH1-1.13'),
        ('4CH1-PR-03', '4CH1-1.36'),
        ('4CH1-PR-04', '4CH1-1.60C'),
        ('4CH1-PR-09', '4CH1-3.8'),
        ('4CH1-PR-10', '4CH1-3.15'),
        ('4CH1-PR-11', '4CH1-3.16')
    )
    UPDATE knowledge_edges e
    SET source_node_id = sp.id
    FROM mapping m
    JOIN knowledge_nodes pr ON pr.code = m.pr_code
    JOIN knowledge_nodes sp ON sp.code = m.sp_code
    WHERE e.source_node_id = pr.id
      AND e.relation_type = 'REQUIRES_PREREQUISITE'
      AND NOT EXISTS (           -- never collide with an existing identity (uq_edge)
          SELECT 1 FROM knowledge_edges d
          WHERE d.source_node_id = sp.id
            AND d.target_node_id = e.target_node_id
            AND d.relation_type = 'REQUIRES_PREREQUISITE');
    GET DIAGNOSTICS updated = ROW_COUNT;

    IF updated <> 12 THEN
        RAISE EXCEPTION 'V54 retarget: expected to move exactly 12 validated practical prerequisite edges, moved % — store drift, resolve manually', updated;
    END IF;

    IF EXISTS (
        SELECT 1 FROM knowledge_edges e
        JOIN knowledge_nodes s ON s.id = e.source_node_id
        WHERE e.relation_type = 'REQUIRES_PREREQUISITE'
          AND s.code IN ('4CH1-PR-01', '4CH1-PR-02', '4CH1-PR-03', '4CH1-PR-04',
                         '4CH1-PR-09', '4CH1-PR-10', '4CH1-PR-11')
    ) THEN
        RAISE EXCEPTION 'V54 retarget: ad-hoc practical prerequisite edges remain after update — destination collision, resolve manually';
    END IF;
END
$$;

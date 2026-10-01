-- V55: retarget the 7 batch-5/6/7/11 practical REQUIRES_PREREQUISITE edges from
-- the ad-hoc practical-node codes to their real core-practical spec statements —
-- the completion of V54's retarget after the batch-11 core sync (96e7bec) and
-- the operator's re-activation seeded those edges at their then-current ad-hoc
-- sources (4CH1-PR-05, 4CH1-PR-06, 4CH1-PR-07, 4CH1-PR-08, 4CH1-PR-12).
--
-- V54's mapping was drafted against the batch-4 store (12 edges, PR-01..11) and
-- correctly moved exactly those 12 rows on this database. The 7 edges authored
-- in syllabai-resources batches 5/6/7/11 (PR-05/06/07/08 x1 + PR-12 x3) entered
-- this database through re-activation with the batch-11 snapshot and need the
-- extended mapping — the same one scripts/retarget_pr_endpoints.py already
-- applied to the store bytes.
--
-- Same contract as V54: rows move IN PLACE, only source_node_id changes;
-- provenance/rationale/validation_status/created_by are byte-preserved so the
-- next re-activation with the retargeted snapshot resolves every row as reused
-- (identity + provenance contract). Practical NODES and their PART_OF anchors
-- are untouched. Fail-closed: exactly 7 rows must move (any other count is
-- store drift — resolve manually, never guess); structural no-op when there is
-- nothing left to retarget.

DO $$
DECLARE
    updated INT := 0;
BEGIN
    -- nothing left to retarget (fresh database, or already completed)
    IF NOT EXISTS (
        SELECT 1 FROM knowledge_edges e
        JOIN knowledge_nodes s ON s.id = e.source_node_id
        WHERE e.relation_type = 'REQUIRES_PREREQUISITE'
          AND s.code IN ('4CH1-PR-05', '4CH1-PR-06', '4CH1-PR-07',
                         '4CH1-PR-08', '4CH1-PR-12')
    ) THEN
        RETURN;
    END IF;

    -- every target spec-statement node must exist before any row moves
    IF (SELECT COUNT(*) FROM knowledge_nodes WHERE code IN
            ('4CH1-2.14', '4CH1-2.21', '4CH1-2.42', '4CH1-2.43C', '4CH1-4.43C')) <> 5 THEN
        RAISE EXCEPTION 'V55 retarget: target spec-statement node(s) missing — seed drift, resolve manually';
    END IF;

    WITH mapping(pr_code, sp_code) AS (VALUES
        ('4CH1-PR-05', '4CH1-2.14'),
        ('4CH1-PR-06', '4CH1-2.21'),
        ('4CH1-PR-07', '4CH1-2.42'),
        ('4CH1-PR-08', '4CH1-2.43C'),
        ('4CH1-PR-12', '4CH1-4.43C')
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

    IF updated <> 7 THEN
        RAISE EXCEPTION 'V55 retarget: expected to move exactly 7 validated practical prerequisite edges, moved % — store drift, resolve manually', updated;
    END IF;

    IF EXISTS (
        SELECT 1 FROM knowledge_edges e
        JOIN knowledge_nodes s ON s.id = e.source_node_id
        WHERE e.relation_type = 'REQUIRES_PREREQUISITE'
          AND s.code IN ('4CH1-PR-05', '4CH1-PR-06', '4CH1-PR-07',
                         '4CH1-PR-08', '4CH1-PR-12')
    ) THEN
        RAISE EXCEPTION 'V55 retarget: ad-hoc practical prerequisite edges remain after update — destination collision, resolve manually';
    END IF;
END
$$;

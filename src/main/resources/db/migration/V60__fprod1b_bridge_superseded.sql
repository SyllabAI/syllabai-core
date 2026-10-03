-- V60 — F-PROD-1b status lane: the drifted GLM-OCR bridge records are SUPERSEDED-BY-REINGEST.
--
-- Governing records (repo SyllabAI/syllabai):
--   ruling  bench/review/fprod1-bridge-review-20261003/FPROD1B-IDENTITY-RULING-2026-10-04.md
--           (operator word 'F-PROD-1b call', trace 1a102e2dfe703779)
--   lane    'run the status lane' (operator trace 1a1030b602705911)
--   preflight copy-on-write rehearsal 2026-10-03 (records scripts/fprod1b_status_lane_preflight.py):
--           drift predicate = exactly 49 rows (25 REVIEW_REQUIRED + 24 OK), undrifted 10;
--           rehearsal flip on the branch -> 0 RR / 49 SUPERSEDED / 10 OK, zero collateral,
--           review_findings / document ids byte-intact; rehearsal branch deleted after.
--
-- What the 49 are: every glm_ocr_bridge_records row whose anchored qp/ms document ids differ
-- from the paper's serving canonical ids on exam_papers. The bridge-linked documents are
-- superseded campaign-era imports (REJECTED, 0 chunks); the serving lineage is VALIDATED and
-- rev2-complete. The REVIEW_REQUIRED gate was blocking validate-all on 25 SERVING papers while
-- guarding an import that serves nothing (the F-PROD-1b sub-finding, 49/59 drifted).
--
-- Fresh-database safety: on an empty bridge table the census block is skipped and the UPDATE
-- matches nothing; both DDL statements are safe on the V13 schema.

-- 1. extend the status domain (V13 created it as IN ('OK', 'REVIEW_REQUIRED'))
ALTER TABLE glm_ocr_bridge_records DROP CONSTRAINT ck_glm_ocr_reconciliation_status;
ALTER TABLE glm_ocr_bridge_records ADD CONSTRAINT ck_glm_ocr_reconciliation_status
    CHECK (reconciliation_status IN ('OK', 'REVIEW_REQUIRED', 'SUPERSEDED'));

-- 2. the guarded flip: census gate first (abort the deploy on any drift from the preflight),
--    then supersede by the drift PREDICATE (the id list is pinned in the preflight receipts).
DO $$
DECLARE n int;
BEGIN
    IF EXISTS (SELECT 1 FROM glm_ocr_bridge_records) THEN
        SELECT count(*) INTO n
        FROM glm_ocr_bridge_records b
        JOIN exam_papers p ON p.id = b.paper_id
        WHERE b.qp_document_id IS DISTINCT FROM p.question_paper_document_id
           OR b.ms_document_id IS DISTINCT FROM p.mark_scheme_document_id;
        IF n <> 49 THEN
            RAISE EXCEPTION 'F-PROD-1b drift census = %, expected 49 - aborting per FPROD1B-IDENTITY-RULING-2026-10-04', n;
        END IF;
        UPDATE glm_ocr_bridge_records b
        SET reconciliation_status = 'SUPERSEDED'
        FROM exam_papers p
        WHERE p.id = b.paper_id
          AND (b.qp_document_id IS DISTINCT FROM p.question_paper_document_id
            OR b.ms_document_id IS DISTINCT FROM p.mark_scheme_document_id);
    END IF;
END $$;

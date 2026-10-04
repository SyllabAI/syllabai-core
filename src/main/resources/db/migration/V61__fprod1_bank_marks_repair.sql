-- V61 — F-PROD-1 bank-repair lane: the 4 print-pass defect rows' bank marks repaired
-- to the printed totals (1 -> 13 / 11 / 11 / 9).
--
-- Authority chain (records repo SyllabAI/syllabai):
--   operator word  'repair the 4 bank entries' (trace 1a1047d4ad41baf9)
--   referral       bench/review/fprod1-print-pass-20261004/REPORT.md P3 — worksheet v3
--                  rows defect(bank-repair lane): ms-only rows serving a 1-mark partial
--                  entry against 9-13-mark prints (records 2ffbbb3, Session 183)
--   method         bench/review/psaxis-review-2026-09-28/bank-defect-lane-20261002/
--                  (laneD standard: variant-pinned printed evidence, double condition,
--                  fail-closed, parts untouched, no state flips, no audit rows)
--   plan           scripts/bankrepair20261004/bankrepair_plan.json
--                  sha256 5cda1a0d5afcb44c5cb8e7120dcdac719914846bc401d23c0e0c77a7bf3712ae
--
-- The four rows (each: QP printed total == MS printed/chunked total == print-pass value;
-- bank stem attribution to the own-variant serving QP doc at 0.98-1.00 with cross-variant
-- margins 0.28-0.46; qv.source_document_id = the checksum-verified serving QP doc):
--   q10-6d968517  4CH0/1C  June 2013 Q10   1 -> 13
--   q11-8b9f4957  4CH0/1C  June 2017 Q11   1 -> 11
--   q12-23323bab  4CH0/1C  June 2018 Q12   1 -> 11
--   q11-b4e24b82  4CH0/1CR June 2017 Q11   1 -> 9
-- Marks only: question_parts, mark_schemes, mark_points, validation states, audit — untouched.
--
-- Mechanics precedent: the 2026-10-02 bank-defect lane repaired the same defect class
-- (4 rows banked as 1) with double-condition-guarded updates x2 tables and an exact
-- in-tx marks-sum delta (+26 there); census pins unchanged.
--
-- Fresh-database safety: on an empty questions table the census block is skipped and
-- the census block is skipped and the UPDATEs match nothing.

DO $$
DECLARE
    n int;
    qv_marks_before int;
    q_marks_before int;
    audit_floor constant int := 5586;  -- probe max 2026-10-04; monotonic floor, not exact
BEGIN
    IF NOT EXISTS (SELECT 1 FROM campaign_db_identity WHERE campaign_label = 'T-C04-CAMPAIGN') THEN
        RETURN;  -- fresh/test databases never claim campaign identity (V15 discipline:
                 -- the identity row is a claim act, never a migration side effect),
                 -- so the census-gated repair is a no-op there by construction
    END IF;

    -- ── census pins (exact; any drift aborts the deploy) ─────────────────────
    SELECT count(*) INTO n FROM exam_papers WHERE validation_state = 'VALIDATED';
    IF n <> 91 THEN RAISE EXCEPTION 'V61 pin drift: VALIDATED papers = %, expected 91', n; END IF;
    SELECT count(*) INTO n FROM exam_papers WHERE validation_state = 'REJECTED';
    IF n <> 13 THEN RAISE EXCEPTION 'V61 pin drift: REJECTED papers = %, expected 13', n; END IF;

    IF (SELECT count(*) FROM question_versions) <> 1526
        OR (SELECT count(*) FROM question_versions WHERE validation_state = 'VALIDATED') <> 1465
        OR (SELECT count(*) FROM question_versions WHERE validation_state = 'SUGGESTED') <> 59
        OR (SELECT count(*) FROM question_versions WHERE validation_state = 'REJECTED') <> 2
    THEN RAISE EXCEPTION 'V61 pin drift: question_versions census'; END IF;

    IF (SELECT count(*) FROM questions) <> 1526
        OR (SELECT count(*) FROM questions WHERE active) <> 954
    THEN RAISE EXCEPTION 'V61 pin drift: questions census'; END IF;

    IF (SELECT count(*) FROM question_parts) <> 7183
        OR (SELECT coalesce(sum(marks), 0) FROM question_parts) <> 9068
    THEN RAISE EXCEPTION 'V61 pin drift: question_parts census'; END IF;

    IF (SELECT count(*) FROM mark_schemes) <> 1504
        OR (SELECT count(*) FROM mark_schemes WHERE validation_state = 'VALIDATED') <> 1360
        OR (SELECT count(*) FROM mark_schemes WHERE validation_state = 'SUGGESTED') <> 144
    THEN RAISE EXCEPTION 'V61 pin drift: mark_schemes census'; END IF;

    IF (SELECT count(*) FROM mark_points) <> 5883
    THEN RAISE EXCEPTION 'V61 pin drift: mark_points census'; END IF;

    IF (SELECT count(*) FROM glm_ocr_bridge_records WHERE reconciliation_status = 'OK') <> 10
        OR (SELECT count(*) FROM glm_ocr_bridge_records WHERE reconciliation_status = 'SUPERSEDED') <> 49
        OR (SELECT count(*) FROM glm_ocr_bridge_records WHERE reconciliation_status = 'REVIEW_REQUIRED') <> 0
    THEN RAISE EXCEPTION 'V61 pin drift: bridge census'; END IF;

    SELECT coalesce(max(id), 0) INTO n FROM content_review_audit;
    IF n < audit_floor THEN
        RAISE EXCEPTION 'V61 audit floor: max = % < % (rollback/restore suspected)', n, audit_floor;
    END IF;

    -- ── self-measured before-sums (in-tx; delta must be exactly +40) ─────────
    SELECT coalesce(sum(marks), 0) INTO qv_marks_before FROM question_versions;
    SELECT coalesce(sum(marks), 0) INTO q_marks_before FROM questions;

    -- ── 4 double-condition-guarded repairs (question_versions) ───────────────
    UPDATE question_versions qv SET marks = 13
    WHERE qv.id = 'f7f4d9f3-98d8-462d-9084-2a35402a25af' AND qv.marks = 1 AND qv.version = 1
      AND EXISTS (SELECT 1 FROM questions q JOIN exam_papers p ON p.id = q.exam_paper_id
                  WHERE q.id = qv.question_id AND q.external_ref = 'q10-6d968517'
                    AND p.paper_code = '4CH0/1C' AND p.session_label = 'June 2013');
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'V61: q10-6d968517 qv update matched % rows (expected 1)', n; END IF;

    UPDATE question_versions qv SET marks = 11
    WHERE qv.id = '27255b93-53eb-4d6d-a960-e1429829da2c' AND qv.marks = 1 AND qv.version = 1
      AND EXISTS (SELECT 1 FROM questions q JOIN exam_papers p ON p.id = q.exam_paper_id
                  WHERE q.id = qv.question_id AND q.external_ref = 'q11-8b9f4957'
                    AND p.paper_code = '4CH0/1C' AND p.session_label = 'June 2017');
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'V61: q11-8b9f4957 qv update matched % rows (expected 1)', n; END IF;

    UPDATE question_versions qv SET marks = 11
    WHERE qv.id = 'a8e9c7d3-d70b-4485-a24c-aaddf28fded4' AND qv.marks = 1 AND qv.version = 1
      AND EXISTS (SELECT 1 FROM questions q JOIN exam_papers p ON p.id = q.exam_paper_id
                  WHERE q.id = qv.question_id AND q.external_ref = 'q12-23323bab'
                    AND p.paper_code = '4CH0/1C' AND p.session_label = 'June 2018');
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'V61: q12-23323bab qv update matched % rows (expected 1)', n; END IF;

    UPDATE question_versions qv SET marks = 9
    WHERE qv.id = 'd3733399-d8ed-496f-9b52-49ea4e249743' AND qv.marks = 1 AND qv.version = 1
      AND EXISTS (SELECT 1 FROM questions q JOIN exam_papers p ON p.id = q.exam_paper_id
                  WHERE q.id = qv.question_id AND q.external_ref = 'q11-b4e24b82'
                    AND p.paper_code = '4CH0/1CR' AND p.session_label = 'June 2017');
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'V61: q11-b4e24b82 qv update matched % rows (expected 1)', n; END IF;

    -- ── 4 double-condition-guarded repairs (questions) ───────────────────────
    UPDATE questions q SET marks = 13
    WHERE q.id = 'a7b97aad-1934-4c5f-8fc7-ae262dc41b26' AND q.marks = 1
      AND q.external_ref = 'q10-6d968517'
      AND EXISTS (SELECT 1 FROM exam_papers p WHERE p.id = q.exam_paper_id
                  AND p.paper_code = '4CH0/1C' AND p.session_label = 'June 2013');
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'V61: q10-6d968517 q update matched % rows (expected 1)', n; END IF;

    UPDATE questions q SET marks = 11
    WHERE q.id = '78a997c3-181d-4045-97f0-fc9a6cc40086' AND q.marks = 1
      AND q.external_ref = 'q11-8b9f4957'
      AND EXISTS (SELECT 1 FROM exam_papers p WHERE p.id = q.exam_paper_id
                  AND p.paper_code = '4CH0/1C' AND p.session_label = 'June 2017');
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'V61: q11-8b9f4957 q update matched % rows (expected 1)', n; END IF;

    UPDATE questions q SET marks = 11
    WHERE q.id = '51b434af-7735-47f0-bef1-d6142871dfb6' AND q.marks = 1
      AND q.external_ref = 'q12-23323bab'
      AND EXISTS (SELECT 1 FROM exam_papers p WHERE p.id = q.exam_paper_id
                  AND p.paper_code = '4CH0/1C' AND p.session_label = 'June 2018');
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'V61: q12-23323bab q update matched % rows (expected 1)', n; END IF;

    UPDATE questions q SET marks = 9
    WHERE q.id = 'f970bb4c-16ac-4e12-9db8-4d8a1820b4b5' AND q.marks = 1
      AND q.external_ref = 'q11-b4e24b82'
      AND EXISTS (SELECT 1 FROM exam_papers p WHERE p.id = q.exam_paper_id
                  AND p.paper_code = '4CH0/1CR' AND p.session_label = 'June 2017');
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n <> 1 THEN RAISE EXCEPTION 'V61: q11-b4e24b82 q update matched % rows (expected 1)', n; END IF;

    -- ── in-tx post-asserts: after-values + exact marks-sum delta (+40) ───────
    IF (SELECT qv.marks FROM question_versions qv WHERE qv.id = 'f7f4d9f3-98d8-462d-9084-2a35402a25af') <> 13
        OR (SELECT qv.marks FROM question_versions qv WHERE qv.id = '27255b93-53eb-4d6d-a960-e1429829da2c') <> 11
        OR (SELECT qv.marks FROM question_versions qv WHERE qv.id = 'a8e9c7d3-d70b-4485-a24c-aaddf28fded4') <> 11
        OR (SELECT qv.marks FROM question_versions qv WHERE qv.id = 'd3733399-d8ed-496f-9b52-49ea4e249743') <> 9
        OR (SELECT q.marks FROM questions q WHERE q.id = 'a7b97aad-1934-4c5f-8fc7-ae262dc41b26') <> 13
        OR (SELECT q.marks FROM questions q WHERE q.id = '78a997c3-181d-4045-97f0-fc9a6cc40086') <> 11
        OR (SELECT q.marks FROM questions q WHERE q.id = '51b434af-7735-47f0-bef1-d6142871dfb6') <> 11
        OR (SELECT q.marks FROM questions q WHERE q.id = 'f970bb4c-16ac-4e12-9db8-4d8a1820b4b5') <> 9
    THEN RAISE EXCEPTION 'V61: after-image assertion failed'; END IF;

    IF (SELECT coalesce(sum(marks), 0) FROM question_versions) - qv_marks_before <> 40
        OR (SELECT coalesce(sum(marks), 0) FROM questions) - q_marks_before <> 40
    THEN RAISE EXCEPTION 'V61: marks-sum delta != +40 (collateral write suspected)'; END IF;
END $$;

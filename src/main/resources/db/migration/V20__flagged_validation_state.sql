-- V20 — FLAGGED content-review state (Master Spec §7 lifecycle extension).
--
-- The teacher review lifecycle gains a fourth state, FLAGGED, on all three
-- content entities (exam_papers, question_versions, mark_schemes):
--
--   SUGGESTED -> VALIDATED   (teacher validates — the serving gate for
--                              structured question versions)
--   SUGGESTED -> REJECTED    (teacher rejects — terminal)
--   SUGGESTED -> FLAGGED     (teacher flags — needs a second look; blocks
--                              serving exactly like SUGGESTED, but is visible
--                              as "attention required" rather than "new")
--   VALIDATED -> FLAGGED     (defect found after validation — serving stops
--                              immediately; unflag returns to SUGGESTED, never
--                              straight back to VALIDATED)
--   FLAGGED   -> SUGGESTED   (unflag — re-validation required)
--
-- Serving boundary: a STRUCTURED question version serves ONLY when VALIDATED,
-- so a FLAGGED version never serves (same as SUGGESTED). Additionally the
-- paper-level state now gates serving: questions under a REJECTED or FLAGGED
-- paper do not serve even if their versions are VALIDATED (paper-level
-- integrity: a flagged paper signals a systematic defect — wrong source,
-- mis-placement, mass extraction failure).
--
-- Pure CHECK-constraint widening: no data change, no new columns. Existing
-- rows keep their states; 'FLAGGED' is only written by the new review actions.

ALTER TABLE exam_papers DROP CONSTRAINT ck_exam_paper_state;
ALTER TABLE exam_papers ADD CONSTRAINT ck_exam_paper_state
    CHECK (validation_state IN ('SUGGESTED', 'VALIDATED', 'REJECTED', 'FLAGGED'));

ALTER TABLE question_versions DROP CONSTRAINT ck_question_version_state;
ALTER TABLE question_versions ADD CONSTRAINT ck_question_version_state
    CHECK (validation_state IN ('SUGGESTED', 'VALIDATED', 'REJECTED', 'FLAGGED'));

ALTER TABLE mark_schemes DROP CONSTRAINT ck_mark_scheme_state;
ALTER TABLE mark_schemes ADD CONSTRAINT ck_mark_scheme_state
    CHECK (validation_state IN ('SUGGESTED', 'VALIDATED', 'REJECTED', 'FLAGGED'));

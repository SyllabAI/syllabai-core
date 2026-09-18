-- V29: T-C06 corpus ingestion — kind widening + documents validation lifecycle.
--
-- Three additive enum/shape extensions the content-corpus ingestion path needs
-- (CONTENT_CORPUS_ARCHITECTURE.md §13 gap audit); nothing existing changes
-- semantics:
--
--   1. documents.kind += TEXTBOOK, EXTERNAL_NOTES, EXTERNAL_QUESTIONS — the V11
--      enum was paper-shaped; the instructional corpus roles were registered as
--      T-C06 deliverables. The first real tranche is EXTERNAL_NOTES
--      (SME-RevisionNotes/ial-chemistry-17, 196 notes) converted by the
--      syllabai-parser sme-revision-note/1.0.0 adapter.
--   2. documents.validation_state — the T-C05 lifecycle lands on the document
--      itself: every corpus import is born SUGGESTED and nothing serves without
--      human validation. Serving-eligibility predicates will read this column;
--      none exist yet (the T-C07/T-C14 predicates are paper-level), so the
--      column is write-side state today. Existing paper rows backfill to
--      SUGGESTED — semantically inert: their serving gate remains the
--      paper-level exam_papers.validation_state join.
--   3. questions.provenance += EXTERNAL_BANK — third-party question banks
--      (§13); exercised by a later SME-questions tranche, widened here so the
--      enum extensions of this row land as one reviewed migration.
--
-- ADDITIVE + REVERSIBLE. Reverse statements, for reference:
--   ALTER TABLE questions DROP CONSTRAINT ck_question_provenance;
--   ALTER TABLE questions ADD CONSTRAINT ck_question_provenance
--       CHECK (provenance IN ('PAST_PAPER', 'TEACHER_AUTHORED', 'SEED_DEMO'));
--   ALTER TABLE documents DROP CONSTRAINT ck_documents_validation_state;
--   ALTER TABLE documents DROP COLUMN IF EXISTS validation_state;
--   ALTER TABLE documents DROP CONSTRAINT ck_documents_kind;
--   ALTER TABLE documents ADD CONSTRAINT ck_documents_kind
--       CHECK (kind IN ('QUESTION_PAPER', 'MARK_SCHEME', 'SYLLABUS', 'OTHER'));
-- (the kind re-narrowing is only safe once no row uses the new kinds)

ALTER TABLE documents DROP CONSTRAINT ck_documents_kind;
ALTER TABLE documents ADD CONSTRAINT ck_documents_kind
    CHECK (kind IN ('QUESTION_PAPER', 'MARK_SCHEME', 'SYLLABUS', 'OTHER',
                    'TEXTBOOK', 'EXTERNAL_NOTES', 'EXTERNAL_QUESTIONS'));

ALTER TABLE documents
    ADD COLUMN validation_state VARCHAR(20) NOT NULL DEFAULT 'SUGGESTED';

ALTER TABLE documents
    ADD CONSTRAINT ck_documents_validation_state
    CHECK (validation_state IN ('SUGGESTED', 'VALIDATED', 'REJECTED', 'FLAGGED'));

ALTER TABLE questions DROP CONSTRAINT ck_question_provenance;
ALTER TABLE questions ADD CONSTRAINT ck_question_provenance
    CHECK (provenance IN ('PAST_PAPER', 'TEACHER_AUTHORED', 'SEED_DEMO',
                          'EXTERNAL_BANK'));

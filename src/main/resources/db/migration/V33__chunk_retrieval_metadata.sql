-- V33: retrieval metadata substrate on document_chunks (Embedding v2 bundle, plan §6.3).
--
-- WHY NOW: the serving scoping predicate (T-C07, V28-era) resolves a chunk's
-- curriculum ONLY through the exam_papers join (QP/MS document ids). The
-- knowledge layer about to be ingested (SME revision notes → EXTERNAL_NOTES,
-- spec statements → SYLLABUS, textbooks → TEXTBOOK) has no exam-paper row, so
-- it would be invisible to every serving path. These columns give every chunk
-- a direct, SQL-queryable identity — "headers are for the vector, columns are
-- for SQL" — and carry the series/year/paper/atom identity the Fetch and
-- Enumerate intents need in P3.
--
-- ADDITIVE + FAIL-CLOSED BY CONSTRUCTION:
--   * every new column is nullable (embed_rev has a default) — existing rev1
--     rows are NOT backfilled and NOT mutated (plan §6: rev1 is untouched
--     until the eval gate passes, then superseded at cut-over);
--   * NULL subject_id can never match the serving predicate (NULL = never
--     served) so unscoped or unresolvable chunks stay invisible, same
--     fail-closed posture as T-C07.
--
-- EMBED_REV POLICY (plan §6): embed_rev is the corpus-generation identity,
-- stamped at chunk insert (NOT at embed time — rev2 changes headers+metadata+
-- atom-chunking while the model stays gemini-embedding-001, so the model
-- string cannot distinguish revisions). Reads filter
-- embed_rev = ChunkVectorRepository.CURRENT_EMBED_REV (1 today). Rollback =
-- flip the constant back. rev1 rows are deleted at the R5 cut-over.
--
-- Reverse (rollback) statements, for reference:
--   DROP INDEX IF EXISTS ix_document_chunks_spec_codes;
--   DROP INDEX IF EXISTS ix_document_chunks_subject_year_series;
--   DROP INDEX IF EXISTS ix_document_chunks_subject_kind;
--   ALTER TABLE document_chunks
--     DROP COLUMN IF EXISTS embed_rev,
--     DROP COLUMN IF EXISTS spec_codes,
--     DROP COLUMN IF EXISTS atom_number,
--     DROP COLUMN IF EXISTS paper_code,
--     DROP COLUMN IF EXISTS year,
--     DROP COLUMN IF EXISTS series,
--     DROP COLUMN IF EXISTS subject_id,
--     DROP COLUMN IF EXISTS kind;

ALTER TABLE document_chunks
    ADD COLUMN kind        VARCHAR(20),
    ADD COLUMN subject_id  UUID REFERENCES subjects (id),
    ADD COLUMN series      VARCHAR(3),
    ADD COLUMN year        INT,
    ADD COLUMN paper_code  VARCHAR(20),
    ADD COLUMN atom_number VARCHAR(10),
    ADD COLUMN spec_codes  JSONB,
    ADD COLUMN embed_rev   INT NOT NULL DEFAULT 1 CHECK (embed_rev > 0);

-- kind is denormalized from documents at write time (immutable after insert);
-- values mirror ck_documents_kind (V29). Nullable: rev1 rows are never touched.
ALTER TABLE document_chunks
    ADD CONSTRAINT ck_document_chunks_kind CHECK (kind IS NULL OR kind IN
        ('QUESTION_PAPER', 'MARK_SCHEME', 'SYLLABUS', 'OTHER',
         'TEXTBOOK', 'EXTERNAL_NOTES', 'EXTERNAL_QUESTIONS'));

-- series is the canonical session enum (plan §8.1): raw labels like
-- "Summer 2019" must never be stored — "Summer"/"June" map to JUN.
ALTER TABLE document_chunks
    ADD CONSTRAINT ck_document_chunks_series
    CHECK (series IS NULL OR series IN ('JAN', 'JUN', 'NOV'));

-- Serving + Enumerate indexes. Partial (subject-bearing rows only): rev1 rows
-- (subject_id IS NULL) are excluded, keeping the indexes exactly as large as
-- the corpus that can actually be served by the new branches.
CREATE INDEX ix_document_chunks_subject_kind
    ON document_chunks (subject_id, kind) WHERE subject_id IS NOT NULL;
CREATE INDEX ix_document_chunks_subject_year_series
    ON document_chunks (subject_id, year, series) WHERE subject_id IS NOT NULL;
CREATE INDEX ix_document_chunks_spec_codes
    ON document_chunks USING gin (spec_codes) WHERE spec_codes IS NOT NULL;

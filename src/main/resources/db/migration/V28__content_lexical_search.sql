-- T-C14 lexical retrieval (arm B): BM25-style full-text search over document_chunks.
--
-- RENUMBERED from the prepared package draft: RETRIEVAL_FRONTIER_PREPARED_PACKAGE_2026-09-17.md
-- §3 drafted this as V27, but V27 was taken by the revision-notes corpus pilot
-- (V27__revision_notes.sql) before this migration landed. V28 is the next free
-- version. Recorded here so the draft/provenance chain stays traceable.
--
-- ADDITIVE + REVERSIBLE: no existing column or row is modified or dropped.
-- Reverse (rollback) statements, for reference:
--   DROP INDEX IF EXISTS idx_document_chunks_content_tsv;
--   ALTER TABLE document_chunks DROP COLUMN IF EXISTS content_tsv;
--
-- GENERATED ALWAYS ... STORED: the lexical index is an immutable derivation of
-- content, maintained by Postgres on every write — no backfill step, no dual
-- write path, and the index can never drift from the text it indexes. The
-- 'english' text search configuration is pinned at column definition; changing
-- it later requires the reverse statements above (it is baked into the
-- generated column expression).
ALTER TABLE document_chunks
  ADD COLUMN content_tsv tsvector
  GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX idx_document_chunks_content_tsv
  ON document_chunks USING GIN (content_tsv);

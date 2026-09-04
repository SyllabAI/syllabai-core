-- V11: content module — canonical document store + pgvector retrieval (T-013)
-- Master Spec §6.4 (Content context), §8 (canonical document format),
-- §9 (content-processing architecture: canonical → retrieval index), §13 (VectorStore).
--
-- Science-core note: no BKT/BDT/decay table is touched. This migration adds the
-- content-side retrieval substrate T-024 (KA-RAG) builds on:
--   * documents      — sealed syllabai-parser canonical output (schema 1.0), stored
--                      verbatim as JSONB with its provenance (§8 checksums, §17);
--   * document_chunks — deterministic element-ordered chunks with element_ids
--                      provenance and pgvector embeddings (nullable: chunks land
--                      first, embedding is a separate, re-runnable operation);
--   * model_versions seed — embeddings are an LLM touchpoint → registered per §19.

CREATE EXTENSION IF NOT EXISTS vector;

-- ── documents: the canonical store (§6.4 Document/DocumentSource/provenance) ──
CREATE TABLE documents (
    id                    UUID PRIMARY KEY,
    document_id           VARCHAR(80)  NOT NULL,
    schema_version        VARCHAR(10)  NOT NULL,
    doc_version           INT NOT NULL DEFAULT 1 CHECK (doc_version > 0),
    kind                  VARCHAR(20)  NOT NULL
        CONSTRAINT ck_documents_kind CHECK (kind IN
            ('QUESTION_PAPER', 'MARK_SCHEME', 'SYLLABUS', 'OTHER')),
    source_uri            VARCHAR(500) NOT NULL,
    file_name             VARCHAR(300),
    mime_type             VARCHAR(100) NOT NULL,
    checksum              VARCHAR(128) NOT NULL,
    checksum_algorithm    VARCHAR(20)  NOT NULL DEFAULT 'SHA-256',
    page_count            INT NOT NULL CHECK (page_count > 0),
    element_count         INT NOT NULL CHECK (element_count >= 0),
    text_element_count    INT NOT NULL CHECK (text_element_count >= 0),
    chunk_count           INT NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
    source_engine         VARCHAR(60)  NOT NULL,
    source_engine_version VARCHAR(40)  NOT NULL,
    extracted_at          TIMESTAMPTZ,
    canonical_json        JSONB NOT NULL,
    ingested_by           UUID,
    created_at            TIMESTAMPTZ NOT NULL
);

-- parser-issued canonical id is stable across re-ingests; (id, version) unique
CREATE UNIQUE INDEX uq_documents_canonical_id ON documents (document_id, doc_version);
-- idempotent corpus loads: same source file = same checksum
CREATE UNIQUE INDEX uq_documents_checksum ON documents (checksum);
CREATE INDEX ix_documents_kind ON documents (kind);
CREATE INDEX ix_documents_created ON documents (created_at DESC);

-- ── document_chunks: deterministic chunks + embeddings (§13 VectorStore rows) ──
-- The embedding column is NOT mapped in JPA (Hibernate cannot map pgvector):
-- ChunkVectorRepository owns it via JdbcTemplate with ?::vector casts.
CREATE TABLE document_chunks (
    id              UUID PRIMARY KEY,
    document_row_id UUID NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    chunk_index     INT NOT NULL CHECK (chunk_index >= 0),
    content         TEXT NOT NULL,
    page_start      INT,
    page_end        INT,
    element_ids     JSONB NOT NULL,
    token_estimate  INT NOT NULL CHECK (token_estimate > 0),
    embedding       vector(768),
    embedding_model VARCHAR(60),
    embedded_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_document_chunk UNIQUE (document_row_id, chunk_index)
);

CREATE INDEX ix_document_chunks_document ON document_chunks (document_row_id);
CREATE INDEX ix_document_chunks_pending ON document_chunks (document_row_id)
    WHERE embedding IS NULL;
-- cosine nearest-neighbour search (§13 vector retrieval)
CREATE INDEX ix_document_chunks_embedding ON document_chunks
    USING hnsw (embedding vector_cosine_ops);

-- ── §19 reproducibility: register the embedding model version ────────────────
INSERT INTO model_versions (id, registry_key, version, params, provenance, notes, created_at) VALUES
    ('70000000-0000-0000-0000-000000000010', 'content-embedding', '1.0.0',
     '{"provider":"gemini","model":"text-embedding-004","dimension":768,
       "documentTaskType":"RETRIEVAL_DOCUMENT","queryTaskType":"RETRIEVAL_QUERY",
       "failover":"none-by-design","batching":"spring-ai-default"}',
     'T-013; Master Spec §13/§19; ADR-009 free-tier Gemini embeddings',
     'Canonical-document chunk embeddings and retrieval queries. No failover: a mixed-model index would be inconsistent, so an unavailable provider fails loudly instead of degrading silently.',
     now());

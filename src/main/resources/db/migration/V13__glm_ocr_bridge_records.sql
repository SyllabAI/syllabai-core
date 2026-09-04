-- V13: T-C02 — GLM-OCR verified-parser-output bridge record (content-ops).
-- Master Spec §7 (SUGGESTED until teacher validation), §17 (provenance), T-C02 contract
-- (docs/t-c02-bridge-contract.md).
--
-- Smallest justified persistence structure for everything the existing assessment
-- model cannot represent from the GLM-OCR parser contract:
--   * the verbatim QP/MS drafts (MCQ options, QWC flags, guidance vocabulary,
--     IC table, numbering style, answer prompts, figure refs, warnings, marks-known
--     states, log/publication identifiers) — nothing is discarded;
--   * the verbatim parser reconciliation (QP↔MS findings incl. the audited
--     October Q18 mark-total conflict and the 1A 80-vs-120 paper-total conflict);
--   * the assembled review findings (reconciliation findings + paper-total conflict
--     + QP/MS warnings) in one queryable JSONB column for the teacher review surface.
--
-- Idempotency spine: one bridge record per canonical (qp, ms) document-identity pair
-- (deterministic parser identities — never generated here); re-running the same pair
-- resolves to this record. Canonical documents stay checksum-idempotent in
-- `documents` (V11); assessment rows stay guarded by exam_papers (V8).

CREATE TABLE glm_ocr_bridge_records (
    id                    UUID PRIMARY KEY,
    paper_id              UUID NOT NULL REFERENCES exam_papers (id),
    bridge                VARCHAR(30)  NOT NULL DEFAULT 'glm-ocr-v1',
    qp_document_id        VARCHAR(80)  NOT NULL,
    ms_document_id        VARCHAR(80)  NOT NULL,
    qp_document_row_id    UUID,
    ms_document_row_id    UUID,
    qp_checksum           VARCHAR(128) NOT NULL,
    ms_checksum           VARCHAR(128) NOT NULL,
    extraction_methods    VARCHAR(120) NOT NULL,
    reconciliation_status VARCHAR(20)  NOT NULL
        CONSTRAINT ck_glm_ocr_reconciliation_status CHECK (reconciliation_status IN
            ('OK', 'REVIEW_REQUIRED')),
    review_findings       JSONB NOT NULL,
    qp_draft              JSONB NOT NULL,
    ms_draft              JSONB NOT NULL,
    reconciliation        JSONB NOT NULL,
    created_by            UUID,
    created_at            TIMESTAMPTZ NOT NULL
);

-- one record per imported pair: reruns resolve to the existing record, never a second row
CREATE UNIQUE INDEX uq_glm_ocr_bridge_pair ON glm_ocr_bridge_records (qp_document_id, ms_document_id);
-- review surface: paper → its bridge evidence
CREATE UNIQUE INDEX uq_glm_ocr_bridge_paper ON glm_ocr_bridge_records (paper_id);
CREATE INDEX ix_glm_ocr_bridge_reconciliation ON glm_ocr_bridge_records (reconciliation_status);

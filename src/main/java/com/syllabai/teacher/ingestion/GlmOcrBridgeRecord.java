package com.syllabai.teacher.ingestion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * T-C02 bridge record: the durable evidence that one QP/MS pair was imported
 * through the controlled GLM-OCR bridge, holding everything the existing
 * assessment model cannot represent from the parser contract (Master Spec §17
 * provenance; nothing discarded, nothing inferred):
 *
 * <ul>
 *   <li>the verbatim parser QP/MS drafts (MCQ options, QWC, guidance vocabulary,
 *       IC table, numbering style, answer prompts, figure refs with expired signed
 *       URLs, marks-known states, log/publication identifiers, warnings);</li>
 *   <li>the verbatim parser reconciliation (conflicts stay review-visible —
 *       October Q18 mark-total conflict, 1A 80-vs-120 paper-total conflict);</li>
 *   <li>the assembled review findings (reconciliation findings + paper-total
 *       conflict + QP/MS warnings) in one JSONB column for the review surface.</li>
 * </ul>
 *
 * <p>Idempotency spine: the canonical (qp, ms) document-identity pair —
 * deterministic parser identities, never generated here. Relational row ids may
 * be database-generated, but the same imported source resolves to this existing
 * record on rerun.</p>
 */
@Entity
@Table(name = "glm_ocr_bridge_records")
public class GlmOcrBridgeRecord {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "paper_id", nullable = false)
    private UUID paperId;

    /** bridge implementation identity ("glm-ocr-v1") */
    @Column(name = "bridge", nullable = false, length = 30)
    private String bridge = "glm-ocr-v1";

    /** deterministic parser canonical document identities (never generated here) */
    @Column(name = "qp_document_id", nullable = false, length = 80)
    private String qpDocumentId;

    @Column(name = "ms_document_id", nullable = false, length = 80)
    private String msDocumentId;

    /** content-store row ids (documents table) for joins */
    @Column(name = "qp_document_row_id")
    private UUID qpDocumentRowId;

    @Column(name = "ms_document_row_id")
    private UUID msDocumentRowId;

    @Column(name = "qp_checksum", nullable = false, length = 128)
    private String qpChecksum;

    @Column(name = "ms_checksum", nullable = false, length = 128)
    private String msChecksum;

    /** parser extraction methods, e.g. "glm-ocr-qp-v1+glm-ocr-ms-v1" */
    @Column(name = "extraction_methods", nullable = false, length = 120)
    private String extractionMethods;

    /** "OK" | "REVIEW_REQUIRED" — from the parser reconciliation, never resolved here */
    @Column(name = "reconciliation_status", nullable = false, length = 20)
    private String reconciliationStatus;

    /** assembled structured findings for the teacher review surface */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review_findings", nullable = false, columnDefinition = "jsonb")
    private String reviewFindings;

    /** verbatim parser QP draft JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "qp_draft", nullable = false, columnDefinition = "jsonb")
    private String qpDraft;

    /** verbatim parser MS draft JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ms_draft", nullable = false, columnDefinition = "jsonb")
    private String msDraft;

    /** verbatim parser reconciliation JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reconciliation", nullable = false, columnDefinition = "jsonb")
    private String reconciliation;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GlmOcrBridgeRecord() {
        // JPA
    }

    public GlmOcrBridgeRecord(UUID paperId, String qpDocumentId, String msDocumentId,
                              UUID qpDocumentRowId, UUID msDocumentRowId,
                              String qpChecksum, String msChecksum,
                              String extractionMethods, String reconciliationStatus,
                              String reviewFindings, String qpDraft, String msDraft,
                              String reconciliation, UUID createdBy) {
        this.paperId = paperId;
        this.qpDocumentId = qpDocumentId;
        this.msDocumentId = msDocumentId;
        this.qpDocumentRowId = qpDocumentRowId;
        this.msDocumentRowId = msDocumentRowId;
        this.qpChecksum = qpChecksum;
        this.msChecksum = msChecksum;
        this.extractionMethods = extractionMethods;
        this.reconciliationStatus = reconciliationStatus;
        this.reviewFindings = reviewFindings;
        this.qpDraft = qpDraft;
        this.msDraft = msDraft;
        this.reconciliation = reconciliation;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onInsert() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID id() { return id; }
    public UUID paperId() { return paperId; }
    public String bridge() { return bridge; }
    public String qpDocumentId() { return qpDocumentId; }
    public String msDocumentId() { return msDocumentId; }
    public UUID qpDocumentRowId() { return qpDocumentRowId; }
    public UUID msDocumentRowId() { return msDocumentRowId; }
    public String qpChecksum() { return qpChecksum; }
    public String msChecksum() { return msChecksum; }
    public String extractionMethods() { return extractionMethods; }
    public String reconciliationStatus() { return reconciliationStatus; }
    public String reviewFindings() { return reviewFindings; }
    public String qpDraft() { return qpDraft; }
    public String msDraft() { return msDraft; }
    public String reconciliation() { return reconciliation; }
    public UUID createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
}

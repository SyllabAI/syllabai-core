package com.syllabai.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A syllabai-parser canonical document persisted verbatim (Master Spec §6.4/§8).
 * The sealed JSON lands in {@code canonical_json} with its source checksum — the
 * provenance spine every derived row (chunks, citations) hangs from (§17).
 *
 * <p>Idempotency is two-fold: the parser-issued {@code documentId} + {@code docVersion}
 * is unique, and the source {@code checksum} is unique — re-POSTing the same file
 * returns the existing row instead of duplicating content.</p>
 */
@Entity
@Table(name = "documents")
public class Document {

    public enum Kind { QUESTION_PAPER, MARK_SCHEME, SYLLABUS, OTHER }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "document_id", nullable = false, length = 80)
    private String documentId;

    @Column(name = "schema_version", nullable = false, length = 10)
    private String schemaVersion;

    @Column(name = "doc_version", nullable = false)
    private int docVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind;

    @Column(name = "source_uri", nullable = false, length = 500)
    private String sourceUri;

    @Column(name = "file_name", length = 300)
    private String fileName;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "checksum", nullable = false, length = 128)
    private String checksum;

    @Column(name = "checksum_algorithm", nullable = false, length = 20)
    private String checksumAlgorithm = "SHA-256";

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Column(name = "element_count", nullable = false)
    private int elementCount;

    @Column(name = "text_element_count", nullable = false)
    private int textElementCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "source_engine", nullable = false, length = 60)
    private String sourceEngine;

    @Column(name = "source_engine_version", nullable = false, length = 40)
    private String sourceEngineVersion;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canonical_json", nullable = false, columnDefinition = "jsonb")
    private String canonicalJson;

    @Column(name = "ingested_by")
    private UUID ingestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Document() {
        // JPA
    }

    public Document(String documentId, String schemaVersion, int docVersion, Kind kind,
                    String sourceUri, String fileName, String mimeType, String checksum,
                    String checksumAlgorithm, int pageCount, int elementCount,
                    int textElementCount, int chunkCount, String sourceEngine,
                    String sourceEngineVersion, Instant extractedAt, String canonicalJson,
                    UUID ingestedBy) {
        this.documentId = documentId;
        this.schemaVersion = schemaVersion;
        this.docVersion = docVersion;
        this.kind = kind;
        this.sourceUri = sourceUri;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.checksum = checksum;
        this.checksumAlgorithm = checksumAlgorithm;
        this.pageCount = pageCount;
        this.elementCount = elementCount;
        this.textElementCount = textElementCount;
        this.chunkCount = chunkCount;
        this.sourceEngine = sourceEngine;
        this.sourceEngineVersion = sourceEngineVersion;
        this.extractedAt = extractedAt;
        this.canonicalJson = canonicalJson;
        this.ingestedBy = ingestedBy;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public String documentId() { return documentId; }
    public String schemaVersion() { return schemaVersion; }
    public int docVersion() { return docVersion; }
    public Kind kind() { return kind; }
    public String sourceUri() { return sourceUri; }
    public String fileName() { return fileName; }
    public String mimeType() { return mimeType; }
    public String checksum() { return checksum; }
    public String checksumAlgorithm() { return checksumAlgorithm; }
    public int pageCount() { return pageCount; }
    public int elementCount() { return elementCount; }
    public int textElementCount() { return textElementCount; }
    public int chunkCount() { return chunkCount; }
    public String sourceEngine() { return sourceEngine; }
    public String sourceEngineVersion() { return sourceEngineVersion; }
    public Instant extractedAt() { return extractedAt; }
    public String canonicalJson() { return canonicalJson; }
    public UUID ingestedBy() { return ingestedBy; }
    public Instant createdAt() { return createdAt; }

    void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
}

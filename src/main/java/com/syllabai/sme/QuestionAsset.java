package com.syllabai.sme;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Binary asset (diagram image) referenced by SME question stems, parts and
 * solutions. Filenames are corpus-package provided and containment-validated
 * by the ingest service (no path separators, no traversal) — the same
 * discipline as {@code RevisionNoteAsset}. Stored as bytea; the SME question
 * corpus ships ~585 images (~28 MB).
 */
@Entity
@Table(name = "question_asset")
public class QuestionAsset {

    @Id
    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** bytea via SqlTypes.BINARY (the OID/large-object trap — see RevisionNoteAsset). */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "bytes", nullable = false, columnDefinition = "bytea")
    private byte[] bytes;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected QuestionAsset() {
    }

    public QuestionAsset(String filename, String contentType, long sizeBytes,
            byte[] bytes, Instant ingestedAt) {
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.bytes = bytes;
        this.ingestedAt = ingestedAt;
    }

    public String filename() {
        return filename;
    }

    public String contentType() {
        return contentType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public byte[] bytes() {
        return bytes;
    }

    public Instant ingestedAt() {
        return ingestedAt;
    }
}

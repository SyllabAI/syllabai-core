package com.syllabai.revisionnotes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Binary asset (diagram PNG) referenced by revision-note bodies. Filenames are
 * corpus-package provided and validated (no path separators, no traversal) by
 * the ingest service — the same containment discipline the parser lane applies
 * to asset roots. Stored as bytea; the corpus is small (~210 images, ~22 MB).
 */
@Entity
@Table(name = "revision_note_asset")
public class RevisionNoteAsset {

    @Id
    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /**
     * bytea via SqlTypes.BINARY — @Lob byte[] maps to OID/large-object on
     * Postgres (the live-verified "expression is of type bigint" error); the
     * explicit binary type code binds plain bytea like the jsonb pattern.
     */
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "bytes", nullable = false, columnDefinition = "bytea")
    private byte[] bytes;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    protected RevisionNoteAsset() {
    }

    public RevisionNoteAsset(String filename, String contentType, long sizeBytes,
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

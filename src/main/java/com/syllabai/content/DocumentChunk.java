package com.syllabai.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A deterministic, element-aligned slice of a canonical document (T-013, §9 retrieval
 * index). The {@code embedding} vector column is intentionally NOT mapped here —
 * Hibernate cannot map pgvector; {@link ChunkVectorRepository} owns it through
 * JdbcTemplate with explicit {@code ?::vector} casts. Metadata columns
 * ({@code embedding_model}, {@code embedded_at}) are mapped so embedding state is
 * queryable through JPA.
 *
 * <p>Embedding-v2 metadata (V33, plan §6.3): the identity columns mirror the
 * doc-level {@code retrieval} block at write time — kind/subject/series/year/
 * paper_code/atom_number/spec_codes — so SQL can filter without parsing headers
 * and without the exam-paper join (which the knowledge layer has no row in).
 * {@code embed_rev} is the corpus-generation identity stamped at insert
 * ({@link ChunkVectorRepository#CURRENT_EMBED_REV}); rev1 rows carry the default
 * and are never mutated (plan §6).</p>
 */
@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "document_row_id", nullable = false)
    private UUID documentRowId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;

    /** provenance: canonical element_ids in reading order (JSON array, §8) */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "element_ids", nullable = false, columnDefinition = "jsonb")
    private List<String> elementIds;

    @Column(name = "token_estimate", nullable = false)
    private int tokenEstimate;

    @Column(name = "embedding_model", length = 60)
    private String embeddingModel;

    @Column(name = "embedded_at")
    private Instant embeddedAt;

    // ── Embedding-v2 identity (V33) — nullable: rev1 rows are never backfilled ──

    /** denormalized from documents.kind at write time; immutable after insert */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20)
    private Document.Kind kind;

    /** direct scoping anchor for paper-less kinds (notes/spec/textbook) */
    @Column(name = "subject_id")
    private UUID subjectId;

    /** canonical session enum JAN/JUN/NOV (plan §8.1) — never raw labels */
    @Column(name = "series", length = 3)
    private String series;

    @Column(name = "year")
    private Integer year;

    @Column(name = "paper_code", length = 20)
    private String paperCode;

    /** atom identity from the chunk's group_key (q3 → "3"); null for legacy docs */
    @Column(name = "atom_number", length = 10)
    private String atomNumber;

    /** spec-point codes the chunk is anchored to (JSONB array) */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "spec_codes", columnDefinition = "jsonb")
    private List<String> specCodes;

    /** corpus-generation identity (plan §6): 1 = current rev1, flipped at cut-over */
    @Column(name = "embed_rev", nullable = false)
    private int embedRev = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DocumentChunk() {
        // JPA
    }

    public DocumentChunk(UUID documentRowId, int chunkIndex, String content,
                         Integer pageStart, Integer pageEnd, List<String> elementIds,
                         int tokenEstimate) {
        this(documentRowId, chunkIndex, content, pageStart, pageEnd, elementIds,
                tokenEstimate, null);
    }

    /**
     * @param meta write-time identity mirror (V33); null keeps legacy-shape
     *             (all identity columns null, embed_rev = current default)
     */
    public DocumentChunk(UUID documentRowId, int chunkIndex, String content,
                         Integer pageStart, Integer pageEnd, List<String> elementIds,
                         int tokenEstimate, ChunkMetadata meta) {
        this.documentRowId = documentRowId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.elementIds = List.copyOf(elementIds);
        this.tokenEstimate = tokenEstimate;
        if (meta != null) {
            this.kind = meta.kind();
            this.subjectId = meta.subjectId();
            this.series = meta.series();
            this.year = meta.year();
            this.paperCode = meta.paperCode();
            this.atomNumber = meta.atomNumber();
            this.specCodes = meta.specCodes() == null ? null : List.copyOf(meta.specCodes());
            this.embedRev = meta.embedRev();
        }
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID documentRowId() { return documentRowId; }
    public int chunkIndex() { return chunkIndex; }
    public String content() { return content; }
    public Integer pageStart() { return pageStart; }
    public Integer pageEnd() { return pageEnd; }
    public List<String> elementIds() { return elementIds; }
    public int tokenEstimate() { return tokenEstimate; }
    public Document.Kind kind() { return kind; }
    public UUID subjectId() { return subjectId; }
    public String series() { return series; }
    public Integer year() { return year; }
    public String paperCode() { return paperCode; }
    public String atomNumber() { return atomNumber; }
    public List<String> specCodes() { return specCodes; }
    public int embedRev() { return embedRev; }
    public String embeddingModel() { return embeddingModel; }
    public Instant embeddedAt() { return embeddedAt; }
    public Instant createdAt() { return createdAt; }

    void markEmbedded(String embeddingModel, Instant embeddedAt) {
        this.embeddingModel = embeddingModel;
        this.embeddedAt = embeddedAt;
    }
}

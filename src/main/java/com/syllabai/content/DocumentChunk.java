package com.syllabai.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DocumentChunk() {
        // JPA
    }

    public DocumentChunk(UUID documentRowId, int chunkIndex, String content,
                         Integer pageStart, Integer pageEnd, List<String> elementIds,
                         int tokenEstimate) {
        this.documentRowId = documentRowId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.pageStart = pageStart;
        this.pageEnd = pageEnd;
        this.elementIds = List.copyOf(elementIds);
        this.tokenEstimate = tokenEstimate;
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
    public String embeddingModel() { return embeddingModel; }
    public Instant embeddedAt() { return embeddedAt; }
    public Instant createdAt() { return createdAt; }

    void markEmbedded(String embeddingModel, Instant embeddedAt) {
        this.embeddingModel = embeddingModel;
        this.embeddedAt = embeddedAt;
    }
}

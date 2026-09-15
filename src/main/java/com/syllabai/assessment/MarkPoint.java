package com.syllabai.assessment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One mark point of a {@link MarkScheme} — the atomic unit of partial credit (Master
 * Spec §15). A point either targets a specific {@link QuestionPart} (part-scoped) or
 * the whole question (part null). {@code acceptanceCriteria} is the deterministic
 * matching evidence authored/reviewed by teachers; the extractor deliberately leaves
 * it empty rather than inventing criteria.
 */
@Entity
@Table(name = "mark_points")
public class MarkPoint {

    @Id
    @Column(name = "id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mark_scheme_id", nullable = false)
    private MarkScheme markScheme;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_part_id")
    private QuestionPart questionPart;

    /**
     * Read-only scalar mapping of the association's FK column (the established
     * QuestionVersion.questionIdColumn pattern). Lets id-only readers resolve
     * the part reference WITHOUT initializing the LAZY questionPart proxy —
     * critical for out-of-transaction readers (the CLA pipeline: OSIV off,
     * non-transactional service; with field-access entities even id()
     * initializes a proxy and throws LazyInitializationException).
     */
    @Column(name = "question_part_id", insertable = false, updatable = false)
    private UUID questionPartIdColumn;

    /** raw reference from the source document, e.g. "3-a" */
    @Column(name = "ref", length = 20)
    private String ref;

    @Column(name = "ordering", nullable = false)
    private int ordering;

    @Column(name = "text", nullable = false, columnDefinition = "text")
    private String text;

    @Column(name = "marks", nullable = false)
    private int marks;

    /** deterministic acceptance criteria (JSON array of strings), teacher-authored */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "acceptance_criteria", columnDefinition = "jsonb")
    private List<String> acceptanceCriteria = new ArrayList<>();

    @Column(name = "extraction_confidence")
    private Double extractionConfidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MarkPoint() {
        // JPA
    }

    public MarkPoint(MarkScheme markScheme, QuestionPart questionPart, String ref,
                     int ordering, String text, int marks, List<String> acceptanceCriteria,
                     Double extractionConfidence) {
        this.markScheme = markScheme;
        this.questionPart = questionPart;
        this.ref = ref;
        this.ordering = ordering;
        this.text = text;
        this.marks = marks;
        this.acceptanceCriteria = acceptanceCriteria == null
                ? new ArrayList<>() : new ArrayList<>(acceptanceCriteria);
        this.extractionConfidence = extractionConfidence;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID markSchemeId() { return markScheme.id(); }
    public QuestionPart questionPart() { return questionPart; }

    /**
     * Proxy-safe part reference: the read-only scalar column is populated on
     * every load; the association fallback covers freshly-constructed (not yet
     * refreshed) entities inside a transaction.
     */
    public UUID questionPartId() {
        if (questionPartIdColumn != null) {
            return questionPartIdColumn;
        }
        return questionPart == null ? null : questionPart.id();
    }
    public String ref() { return ref; }
    public int ordering() { return ordering; }
    public String text() { return text; }
    public int marks() { return marks; }
    public List<String> acceptanceCriteria() { return List.copyOf(acceptanceCriteria); }
    public Double extractionConfidence() { return extractionConfidence; }
    public Instant createdAt() { return createdAt; }

    /** teacher authors/reviews the deterministic criteria during validation */
    public void setAcceptanceCriteria(List<String> criteria) {
        this.acceptanceCriteria = criteria == null ? new ArrayList<>() : new ArrayList<>(criteria);
    }
}

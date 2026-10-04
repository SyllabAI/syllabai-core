package com.syllabai.learner.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One exam sitting in the imported reference calendar (T-C79, ADR-035 D1
 * ruling 2026-10-04: the calendar is curriculum-IMPORTED reference data —
 * cadence is DATA per board/qualification, never hardcoded, and per-subject
 * availability varies by sitting, exactly as measured on the real Pearson
 * documents: the October IAL sitting carries 7 subjects, the summer
 * International GCSE 49).
 *
 * <p>Honesty contract: every row is a citation. {@code sourceUrl} +
 * {@code retrievedAt} are NOT NULL (V62) — a series row without a citable
 * Pearson-style source document cannot exist (the anti-fabrication rule
 * applies to reference data with the same force as to content). Unpublished
 * windows either don't exist here or carry {@code estimated = true}, which
 * clients must render with "≈", never as fact. Countdowns are derived at
 * read (ADR-031); nothing in this table stores them.</p>
 */
@Entity
@Table(name = "exam_series")
public class ExamSeries {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "board", nullable = false, length = 50)
    private String board;

    @Column(name = "qualification", nullable = false, length = 50)
    private String qualification;

    @Column(name = "series_code", nullable = false, length = 60)
    private String seriesCode;

    @Column(name = "label", nullable = false, length = 120)
    private String label;

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    @Column(name = "entry_deadline")
    private LocalDate entryDeadline;

    @Column(name = "results_date")
    private LocalDate resultsDate;

    @Column(name = "published", nullable = false)
    private boolean published = true;

    @Column(name = "estimated", nullable = false)
    private boolean estimated = false;

    @Column(name = "source_url", nullable = false, length = 500)
    private String sourceUrl;

    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExamSeries() {
    }

    private ExamSeries(UUID id, String board, String qualification, String seriesCode, String label,
                       LocalDate windowStart, LocalDate windowEnd, LocalDate entryDeadline,
                       LocalDate resultsDate, boolean published, boolean estimated,
                       String sourceUrl, Instant retrievedAt, Instant now) {
        this.id = id;
        this.board = board;
        this.qualification = qualification;
        this.seriesCode = seriesCode;
        this.label = label;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.entryDeadline = entryDeadline;
        this.resultsDate = resultsDate;
        this.published = published;
        this.estimated = estimated;
        this.sourceUrl = sourceUrl;
        this.retrievedAt = retrievedAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ExamSeries newImported(UUID id, String board, String qualification, String seriesCode,
                                         String label, LocalDate windowStart, LocalDate windowEnd,
                                         LocalDate entryDeadline, LocalDate resultsDate,
                                         boolean published, boolean estimated, String sourceUrl,
                                         Instant retrievedAt, Instant now) {
        return new ExamSeries(id, board, qualification, seriesCode, label, windowStart, windowEnd,
                entryDeadline, resultsDate, published, estimated, sourceUrl, retrievedAt, now);
    }

    /** a calendar correction: the measured fields move, the citation moves with them */
    public void applyImport(String label, LocalDate windowStart, LocalDate windowEnd,
                            LocalDate entryDeadline, LocalDate resultsDate,
                            boolean published, boolean estimated, String sourceUrl,
                            Instant retrievedAt, Instant now) {
        this.label = label;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.entryDeadline = entryDeadline;
        this.resultsDate = resultsDate;
        this.published = published;
        this.estimated = estimated;
        this.sourceUrl = sourceUrl;
        this.retrievedAt = retrievedAt;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String board() {
        return board;
    }

    public String qualification() {
        return qualification;
    }

    public String seriesCode() {
        return seriesCode;
    }

    public String label() {
        return label;
    }

    public LocalDate windowStart() {
        return windowStart;
    }

    public LocalDate windowEnd() {
        return windowEnd;
    }

    public LocalDate entryDeadline() {
        return entryDeadline;
    }

    public LocalDate resultsDate() {
        return resultsDate;
    }

    public boolean published() {
        return published;
    }

    public boolean estimated() {
        return estimated;
    }

    public String sourceUrl() {
        return sourceUrl;
    }

    public Instant retrievedAt() {
        return retrievedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

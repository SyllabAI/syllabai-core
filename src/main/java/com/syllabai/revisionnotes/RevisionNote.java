package com.syllabai.revisionnotes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One ingested revision note (pilot corpus: Save My Exams-derived IGCSE
 * Chemistry 4CH1). Identity is the corpus package's stable business id
 * (derived from the canonical source slug, e.g. {@code 2-8-1-tests-for-gases}),
 * so learner progress references survive re-ingestion. Tree position is the
 * canonical topic → subtopic → note ordering carried by the package.
 *
 * <p>Licensing: corpus content is internal-pilot material (LICENSE-DATA.md in
 * syllabai-resources) — this surface is authenticated-only by design; nothing
 * here may be exposed anonymously.</p>
 */
@Entity
@Table(name = "revision_note")
public class RevisionNote {

    @Id
    @Column(name = "note_id", nullable = false, length = 256)
    private String noteId;

    @Column(name = "topic_order", nullable = false)
    private int topicOrder;

    @Column(name = "topic_title", nullable = false, length = 512)
    private String topicTitle;

    @Column(name = "subtopic_order", nullable = false)
    private int subtopicOrder;

    @Column(name = "subtopic_title", nullable = false, length = 512)
    private String subtopicTitle;

    @Column(name = "note_order", nullable = false)
    private int noteOrder;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "body_md", nullable = false, columnDefinition = "text")
    private String bodyMd;

    /**
     * jsonb columns carry pre-serialized JSON strings — SqlTypes.JSON makes
     * Hibernate send a json-typed parameter (a bare varchar parameter fails
     * against a real Postgres jsonb column; see the intervention lane's
     * live-verified 500 for the failure mode this avoids).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec_map", nullable = false, columnDefinition = "jsonb")
    private String specMap = "{}";

    /** Comma-joined canonical spec-point codes (e.g. "4CH1-2.44,4CH1-2.45"). */
    @Column(name = "spec_point_codes", nullable = false, columnDefinition = "text")
    private String specPointCodes = "";

    @Column(name = "source_url")
    private String sourceUrl;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @Column(name = "corpus_version", nullable = false, length = 128)
    private String corpusVersion;

    protected RevisionNote() {
    }

    public RevisionNote(String noteId, int topicOrder, String topicTitle,
            int subtopicOrder, String subtopicTitle, int noteOrder,
            String title, String bodyMd, String specMap, String specPointCodes,
            String sourceUrl, Instant ingestedAt, String corpusVersion) {
        this.noteId = noteId;
        this.topicOrder = topicOrder;
        this.topicTitle = topicTitle;
        this.subtopicOrder = subtopicOrder;
        this.subtopicTitle = subtopicTitle;
        this.noteOrder = noteOrder;
        this.title = title;
        this.bodyMd = bodyMd;
        this.specMap = specMap;
        this.specPointCodes = specPointCodes;
        this.sourceUrl = sourceUrl;
        this.ingestedAt = ingestedAt;
        this.corpusVersion = corpusVersion;
    }

    public String noteId() {
        return noteId;
    }

    public int topicOrder() {
        return topicOrder;
    }

    public String topicTitle() {
        return topicTitle;
    }

    public int subtopicOrder() {
        return subtopicOrder;
    }

    public String subtopicTitle() {
        return subtopicTitle;
    }

    public int noteOrder() {
        return noteOrder;
    }

    public String title() {
        return title;
    }

    public String bodyMd() {
        return bodyMd;
    }

    public String specMap() {
        return specMap;
    }

    public String specPointCodes() {
        return specPointCodes;
    }

    public String sourceUrl() {
        return sourceUrl;
    }

    public Instant ingestedAt() {
        return ingestedAt;
    }

    public String corpusVersion() {
        return corpusVersion;
    }
}

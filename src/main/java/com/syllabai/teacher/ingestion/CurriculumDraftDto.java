package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Ingestion contract for syllabai-parser's {@code curriculum-draft.json}
 * (schema 1.1, T-010). Mirrors the parser DTO field-for-field — the JSON file
 * is POSTed verbatim; the two modules share a schema, never a Java dependency
 * (Master Spec §27 contract).
 *
 * <p>Per-node provenance (source section, element ids, page, extraction
 * confidence) is carried through so every KG node the ingestion creates can
 * cite the exact spec location it came from (§17). Everything lands in
 * SUGGESTED validation state — nothing serves until a teacher validates it
 * (Master Spec §7).</p>
 */
public record CurriculumDraftDto(
        @JsonProperty("schemaVersion") String schemaVersion,
        @JsonProperty("board") String board,
        @JsonProperty("qualification") String qualification,
        @JsonProperty("code") String code,
        @JsonProperty("title") String title,
        @JsonProperty("subject") SubjectDraft subject,
        @JsonProperty("units") List<UnitDraft> units,
        @JsonProperty("provenance") DraftProvenance provenance) {

    public static final String SUPPORTED_SCHEMA = "1.1";

    public record SubjectDraft(
            @JsonProperty("code") String code,
            @JsonProperty("name") String name) {
    }

    public record UnitDraft(
            @JsonProperty("code") String code,
            @JsonProperty("title") String title,
            @JsonProperty("topics") List<TopicDraft> topics,
            @JsonProperty("sourceSectionId") String sourceSectionId,
            @JsonProperty("sourceElementIds") List<String> sourceElementIds,
            @JsonProperty("pageNumber") Integer pageNumber,
            @JsonProperty("confidence") double confidence) {
    }

    public record TopicDraft(
            @JsonProperty("code") String code,
            @JsonProperty("title") String title,
            @JsonProperty("subtopics") List<TopicDraft> subtopics,
            @JsonProperty("sourceSectionId") String sourceSectionId,
            @JsonProperty("sourceElementIds") List<String> sourceElementIds,
            @JsonProperty("pageNumber") Integer pageNumber,
            @JsonProperty("confidence") double confidence) {
    }

    public record DraftProvenance(
            @JsonProperty("sourceDocumentId") String sourceDocumentId,
            @JsonProperty("sourceChecksum") String sourceChecksum,
            @JsonProperty("engine") String engine,
            @JsonProperty("engineVersion") String engineVersion,
            @JsonProperty("extractionMethod") String extractionMethod,
            @JsonProperty("validationStatus") String validationStatus) {
    }
}

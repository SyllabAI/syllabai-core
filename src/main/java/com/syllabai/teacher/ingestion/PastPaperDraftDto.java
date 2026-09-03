package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Ingestion contract for syllabai-parser's {@code past-paper-draft.json} (schema 1.0).
 * Mirrors the parser DTO field-for-field — the JSON file is POSTed verbatim; the
 * two modules share a schema, never a Java dependency (Master Spec §27 contract).
 *
 * <p>Everything ingested lands in SUGGESTED validation state — drafts are heuristic
 * v0 output ({@code reviewRequired=true}) and nothing serves to learners until a
 * teacher validates it (Master Spec §7).</p>
 */
public record PastPaperDraftDto(
        @JsonProperty("schemaVersion") String schemaVersion,
        @JsonProperty("paper") PaperMeta paper,
        @JsonProperty("questions") List<QuestionDraft> questions,
        @JsonProperty("markScheme") MarkSchemeDraft markScheme,
        @JsonProperty("extractionMethod") String extractionMethod,
        @JsonProperty("reviewRequired") boolean reviewRequired) {

    public static final String SUPPORTED_SCHEMA = "1.0";

    public record PaperMeta(
            @JsonProperty("board") String board,
            @JsonProperty("qualification") String qualification,
            @JsonProperty("subject") String subject,
            @JsonProperty("unit") String unit,
            @JsonProperty("sessionLabel") String sessionLabel,
            @JsonProperty("paperCode") String paperCode,
            @JsonProperty("questionPaperDocumentId") String questionPaperDocumentId,
            @JsonProperty("markSchemeDocumentId") String markSchemeDocumentId) {
    }

    public record QuestionDraft(
            @JsonProperty("externalRef") String externalRef,
            @JsonProperty("questionNumber") String questionNumber,
            @JsonProperty("prompt") String prompt,
            @JsonProperty("commandWord") String commandWord,
            @JsonProperty("marks") int marks,
            @JsonProperty("questionType") String questionType,
            @JsonProperty("pageNumber") int pageNumber,
            @JsonProperty("confidence") double confidence,
            @JsonProperty("parts") List<PartDraft> parts) {
    }

    public record PartDraft(
            @JsonProperty("label") String label,
            @JsonProperty("prompt") String prompt,
            @JsonProperty("commandWord") String commandWord,
            @JsonProperty("marks") int marks,
            @JsonProperty("confidence") double confidence) {
    }

    public record MarkSchemeDraft(
            @JsonProperty("version") String version,
            @JsonProperty("sourceDocumentId") String sourceDocumentId,
            @JsonProperty("points") List<MarkPointDraft> points) {
    }

    public record MarkPointDraft(
            @JsonProperty("questionRef") String questionRef,
            @JsonProperty("order") int order,
            @JsonProperty("text") String text,
            @JsonProperty("marks") int marks,
            @JsonProperty("acceptance") List<String> acceptance,
            @JsonProperty("confidence") double confidence) {
    }
}

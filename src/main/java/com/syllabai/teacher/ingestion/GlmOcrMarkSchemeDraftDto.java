package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.syllabai.teacher.ingestion.GlmOcrPaperDraftDto.PaperMeta;
import java.util.List;
import java.util.Map;

/**
 * Ingestion contract for syllabai-parser's GLM-OCR mark-scheme draft (schema 1.0,
 * extraction method {@code glm-ocr-ms-v1}). Mirrors the parser's
 * {@code GlmOcrMarkSchemeDraft} field-for-field — the JSON is POSTed verbatim;
 * the two modules share a schema, never a Java dependency (Master Spec §27).
 *
 * <p>Table-first Edexcel structure: entries are question / question-part rows with
 * marking points split on {@code (N)} markers, classified guidance lines, and an
 * optional indicative-content table. The marking-semantics vocabulary (Allow /
 * Ignore / dependent-on-MP / ecf / Or / Any-two-from) survives here as structured
 * metadata; the bridge persists it verbatim and never flattens or repairs it.</p>
 */
public record GlmOcrMarkSchemeDraftDto(
        @JsonProperty("schemaVersion") String schemaVersion,
        @JsonProperty("extractionMethod") String extractionMethod,
        @JsonProperty("reviewRequired") boolean reviewRequired,
        @JsonProperty("paper") PaperMeta paper,
        @JsonProperty("entries") List<MarkSchemeEntry> entries,
        @JsonProperty("questionTotals") Map<String, Integer> questionTotals,
        @JsonProperty("paperTotal") Integer paperTotal,
        @JsonProperty("icTable") IcTable icTable,
        @JsonProperty("warnings") List<String> warnings) {

    public static final String SUPPORTED_SCHEMA = "1.0";

    public GlmOcrMarkSchemeDraftDto {
        entries = entries == null ? List.of() : List.copyOf(entries);
        questionTotals = questionTotals == null ? Map.of() : Map.copyOf(questionTotals);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * One mark-scheme row: a question ("11") or question-part ("13(a)",
     * "13(b)(i)"). {@code marks} is the printed Mark cell — null when
     * rowspan-deferred or absent (never guessed).
     */
    public record MarkSchemeEntry(
            @JsonProperty("entryId") String entryId,
            @JsonProperty("label") String label,
            @JsonProperty("number") int number,
            @JsonProperty("qwc") boolean qwc,
            @JsonProperty("mcq") boolean mcq,
            @JsonProperty("correctOption") String correctOption,
            @JsonProperty("answerText") String answerText,
            @JsonProperty("markPoints") List<MarkPoint> markPoints,
            @JsonProperty("guidance") List<GuidanceLine> guidance,
            @JsonProperty("marks") Integer marks,
            @JsonProperty("marksCellSource") String marksCellSource,
            @JsonProperty("confidence") double confidence) {

        public MarkSchemeEntry {
            markPoints = markPoints == null ? List.of() : List.copyOf(markPoints);
            guidance = guidance == null ? List.of() : List.copyOf(guidance);
        }
    }

    /**
     * One marking point (an "(N)"-terminated segment). {@code marks} null means
     * the segment carried no marker — the bridge materializes 0 (unknown), never
     * a guessed value, with the raw evidence preserved.
     */
    public record MarkPoint(
            @JsonProperty("ordinal") int ordinal,
            @JsonProperty("text") String text,
            @JsonProperty("marks") Integer marks,
            @JsonProperty("dependentOn") List<String> dependentOn,
            @JsonProperty("ecf") boolean ecf,
            @JsonProperty("alternatives") List<String> alternatives,
            @JsonProperty("anyTwoFrom") boolean anyTwoFrom,
            @JsonProperty("reject") boolean reject,
            @JsonProperty("rawText") String rawText) {

        public MarkPoint {
            dependentOn = dependentOn == null ? List.of() : List.copyOf(dependentOn);
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }
    }

    /** One Additional-Guidance line, classified by leading keyword. */
    public record GuidanceLine(
            @JsonProperty("kind") String kind,
            @JsonProperty("text") String text) {
    }

    /** Indicative-content table (QWC questions). */
    public record IcTable(
            @JsonProperty("rows") List<List<String>> rows,
            @JsonProperty("location") String location) {

        public IcTable {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }
}

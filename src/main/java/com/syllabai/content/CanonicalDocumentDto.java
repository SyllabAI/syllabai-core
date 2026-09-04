package com.syllabai.content;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Ingestion contract for syllabai-parser's canonical document JSON (schema 1.0).
 * Mirrors the parser's canonical model field-for-field — the JSON file is POSTed
 * verbatim; the two modules share a schema, never a Java dependency (Master Spec §27
 * contract, same pattern as {@code PastPaperDraftDto}).
 *
 * <p>Field names follow §8 exactly: top-level camelCase, per-element snake_case
 * ({@code element_id}, {@code page_number}, {@code bounding_box}, {@code reading_order},
 * {@code confidence}, {@code source_engine}, {@code source_engine_version}). The core
 * re-validates every invariant the parser guarantees — never trust input across a
 * process boundary — see {@link CanonicalDocumentValidator}.</p>
 */
public record CanonicalDocumentDto(
        @JsonProperty("documentId") String documentId,
        @JsonProperty("schemaVersion") String schemaVersion,
        @JsonProperty("version") Integer version,
        @JsonProperty("source") SourceInfo source,
        @JsonProperty("pageCount") Integer pageCount,
        @JsonProperty("pages") List<PageInfo> pages,
        @JsonProperty("sections") List<SectionInfo> sections,
        @JsonProperty("textBlocks") List<TextBlockElement> textBlocks,
        @JsonProperty("tables") List<TableElement> tables,
        @JsonProperty("figures") List<FigureElement> figures,
        @JsonProperty("equations") List<EquationElement> equations,
        @JsonProperty("provenance") ProvenanceInfo provenance) {

    public static final String SUPPORTED_SCHEMA = "1.0";

    public record SourceInfo(
            @JsonProperty("uri") String uri,
            @JsonProperty("checksum") String checksum,
            @JsonProperty("checksumAlgorithm") String checksumAlgorithm,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("fileName") String fileName) {
    }

    public record PageInfo(
            @JsonProperty("pageNumber") Integer pageNumber,
            @JsonProperty("width") Double width,
            @JsonProperty("height") Double height) {
    }

    public record SectionInfo(
            @JsonProperty("sectionId") String sectionId,
            @JsonProperty("title") String title,
            @JsonProperty("level") Integer level,
            @JsonProperty("pageNumber") Integer pageNumber,
            @JsonProperty("elementIds") List<String> elementIds) {
    }

    public record BoundingBox(
            @JsonProperty("x") Double x,
            @JsonProperty("y") Double y,
            @JsonProperty("width") Double width,
            @JsonProperty("height") Double height,
            @JsonProperty("unit") String unit) {
    }

    public record TextBlockElement(
            @JsonProperty("element_id") String elementId,
            @JsonProperty("element_type") String elementType,
            @JsonProperty("page_number") Integer pageNumber,
            @JsonProperty("bounding_box") BoundingBox boundingBox,
            @JsonProperty("text") String text,
            @JsonProperty("reading_order") Integer readingOrder,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("role") String role,
            @JsonProperty("heading_level") Integer headingLevel,
            @JsonProperty("source_engine") String sourceEngine,
            @JsonProperty("source_engine_version") String sourceEngineVersion) {
    }

    public record TableElement(
            @JsonProperty("element_id") String elementId,
            @JsonProperty("element_type") String elementType,
            @JsonProperty("page_number") Integer pageNumber,
            @JsonProperty("bounding_box") BoundingBox boundingBox,
            @JsonProperty("text") String text,
            @JsonProperty("reading_order") Integer readingOrder,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("rows") List<List<String>> rows,
            @JsonProperty("row_count") Integer rowCount,
            @JsonProperty("column_count") Integer columnCount,
            @JsonProperty("source_engine") String sourceEngine,
            @JsonProperty("source_engine_version") String sourceEngineVersion) {
    }

    public record FigureElement(
            @JsonProperty("element_id") String elementId,
            @JsonProperty("element_type") String elementType,
            @JsonProperty("page_number") Integer pageNumber,
            @JsonProperty("bounding_box") BoundingBox boundingBox,
            @JsonProperty("text") String text,
            @JsonProperty("reading_order") Integer readingOrder,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("format") String format,
            @JsonProperty("source_name") String sourceName,
            @JsonProperty("alt") String alt,
            @JsonProperty("source_engine") String sourceEngine,
            @JsonProperty("source_engine_version") String sourceEngineVersion) {
    }

    public record EquationElement(
            @JsonProperty("element_id") String elementId,
            @JsonProperty("element_type") String elementType,
            @JsonProperty("page_number") Integer pageNumber,
            @JsonProperty("bounding_box") BoundingBox boundingBox,
            @JsonProperty("text") String text,
            @JsonProperty("reading_order") Integer readingOrder,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("latex") String latex,
            @JsonProperty("source_engine") String sourceEngine,
            @JsonProperty("source_engine_version") String sourceEngineVersion) {
    }

    public record ProvenanceInfo(
            @JsonProperty("engine") String engine,
            @JsonProperty("engineVersion") String engineVersion,
            @JsonProperty("extractedAt") String extractedAt,
            @JsonProperty("extractionParams") java.util.Map<String, Object> extractionParams,
            @JsonProperty("application") String application,
            @JsonProperty("schemaVersion") String schemaVersion) {
    }

    /** All elements that can contribute text to the retrieval index, §9. */
    public int textElementCount() {
        return (textBlocks == null ? 0 : textBlocks.size())
                + (tables == null ? 0 : tables.size())
                + (equations == null ? 0 : equations.size());
    }

    /** Every element, text-bearing or not (figures carry bounding boxes only). */
    public int totalElementCount() {
        return textElementCount() + (figures == null ? 0 : figures.size());
    }
}

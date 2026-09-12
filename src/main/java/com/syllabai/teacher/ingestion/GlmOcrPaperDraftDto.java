package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Ingestion contract for syllabai-parser's GLM-OCR question-paper draft (schema 1.0,
 * extraction method {@code glm-ocr-qp-v1}). Mirrors the parser's
 * {@code GlmOcrPaperDraft} field-for-field — the JSON is POSTed verbatim; the two
 * modules share a schema, never a Java dependency (Master Spec §27 contract, same
 * pattern as {@code PastPaperDraftDto}).
 *
 * <p>Everything here is draft evidence ({@code reviewRequired=true}, confidence
 * &lt; 1.0). The bridge persists the full draft verbatim in the bridge record so
 * nothing the assessment model cannot represent (MCQ options, QWC flags, answer
 * prompts, figure refs, numbering style, marks-known states, warnings) is silently
 * discarded.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlmOcrPaperDraftDto(
        @JsonProperty("schemaVersion") String schemaVersion,
        @JsonProperty("extractionMethod") String extractionMethod,
        @JsonProperty("reviewRequired") boolean reviewRequired,
        @JsonProperty("paper") PaperMeta paper,
        @JsonProperty("questions") List<QuestionDraft> questions,
        @JsonProperty("questionTotals") Map<String, Integer> questionTotals,
        @JsonProperty("paperTotal") Integer paperTotal,
        @JsonProperty("sectionTotals") Map<String, Integer> sectionTotals,
        @JsonProperty("frontMatterFigures") List<FigureRef> frontMatterFigures,
        @JsonProperty("warnings") List<String> warnings) {

    public static final String SUPPORTED_SCHEMA = "1.0";

    public GlmOcrPaperDraftDto {
        questions = questions == null ? List.of() : List.copyOf(questions);
        questionTotals = questionTotals == null ? Map.of() : Map.copyOf(questionTotals);
        sectionTotals = sectionTotals == null ? Map.of() : Map.copyOf(sectionTotals);
        frontMatterFigures = frontMatterFigures == null
                ? List.of() : List.copyOf(frontMatterFigures);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Content-derived identity; the canonical document id links to the T-013 store. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaperMeta(
            @JsonProperty("board") String board,
            @JsonProperty("qualification") String qualification,
            @JsonProperty("subject") String subject,
            @JsonProperty("paperReference") String paperReference,
            @JsonProperty("logNumber") String logNumber,
            @JsonProperty("publicationCode") String publicationCode,
            @JsonProperty("session") String session,
            @JsonProperty("examDate") String examDate,
            @JsonProperty("duration") String duration,
            @JsonProperty("canonicalDocumentId") String canonicalDocumentId) {
    }

    /** One extracted question (MCQ or structured with letter/roman parts). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuestionDraft(
            @JsonProperty("questionId") String questionId,
            @JsonProperty("number") int number,
            @JsonProperty("numberingStyle") String numberingStyle,
            @JsonProperty("section") String section,
            @JsonProperty("stem") String stem,
            @JsonProperty("mcq") boolean mcq,
            @JsonProperty("options") List<McqOption> options,
            @JsonProperty("parts") List<PartDraft> parts,
            @JsonProperty("figures") List<FigureRef> figures,
            @JsonProperty("tableElementIds") List<String> tableElementIds,
            @JsonProperty("marks") int marks,
            @JsonProperty("marksKnown") boolean marksKnown,
            @JsonProperty("qwc") boolean qwc,
            @JsonProperty("answerPrompts") List<String> answerPrompts,
            @JsonProperty("confidence") double confidence) {

        public QuestionDraft {
            options = options == null ? List.of() : List.copyOf(options);
            parts = parts == null ? List.of() : List.copyOf(parts);
            figures = figures == null ? List.of() : List.copyOf(figures);
            tableElementIds = tableElementIds == null
                    ? List.of() : List.copyOf(tableElementIds);
            answerPrompts = answerPrompts == null ? List.of() : List.copyOf(answerPrompts);
        }
    }

    /** Letter part, optionally with roman subparts labelled "b-i" style. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PartDraft(
            @JsonProperty("partId") String partId,
            @JsonProperty("label") String label,
            @JsonProperty("text") String text,
            @JsonProperty("marks") Integer marks,
            @JsonProperty("qwc") boolean qwc,
            @JsonProperty("figures") List<FigureRef> figures,
            @JsonProperty("answerPrompts") List<String> answerPrompts,
            @JsonProperty("confidence") double confidence) {

        public PartDraft {
            figures = figures == null ? List.of() : List.copyOf(figures);
            answerPrompts = answerPrompts == null ? List.of() : List.copyOf(answerPrompts);
        }
    }

    /** MCQ option; letters may arrive out of order (documented corpus defect #9). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record McqOption(
            @JsonProperty("letter") String letter,
            @JsonProperty("text") String text) {
    }

    /**
     * Figure reference. Two corpus generations share this contract:
     * <ul>
     *   <li>the audited WPH11 sample (expired signed URLs): {@code availability=
     *       "unavailable-signed-url"}, bytes gone, never fetched, never faked;</li>
     *   <li>the 4CH1 local-asset corpus (parser hardening b837fa0): when the
     *       operator passes {@code --assets-dir} and the reference resolves,
     *       {@code availability="available"} plus the content-derived enrichment
     *       fields {@code assetId} ({@code img:<sha256>}), {@code sha256},
     *       {@code mimeType}, {@code width}, {@code height}, {@code formatMismatch}
     *       — all opt-in and omitted (NON_NULL) for unresolvable references, so
     *       unenriched bundles stay byte-identical.</li>
     * </ul>
     * Unknown fields still fail loud (no IGNORE_UNKNOWN_PROPERTIES anywhere on the
     * bridge): a producer/contract drift must never be silently dropped.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FigureRef(
            @JsonProperty("elementId") String elementId,
            @JsonProperty("sourceName") String sourceName,
            @JsonProperty("format") String format,
            @JsonProperty("url") String url,
            @JsonProperty("availability") String availability,
            @JsonProperty("assetId") String assetId,
            @JsonProperty("sha256") String sha256,
            @JsonProperty("mimeType") String mimeType,
            @JsonProperty("width") Integer width,
            @JsonProperty("height") Integer height,
            @JsonProperty("formatMismatch") Boolean formatMismatch) {
    }
}

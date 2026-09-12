package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Ingestion contract for syllabai-parser's QP/MS mark-reconciliation result
 * (parser {@code GlmOcrMarkReconciliation}, design rule D6). Mirrors the
 * parser's JSON field-for-field — the bridge CONSUMES the parser's findings,
 * it never re-implements reconciliation or silently merges/corrects them.
 *
 * <p>Compares per-question totals (QP printed totals vs MS totals), paper totals,
 * and (advisory) presence of MS entries for QP questions. Real-corpus examples the
 * bridge must keep review-visible: October 2025 Unit 1 Q18 (part-marks sum 2 vs
 * printed total 8) and October 2025 Unit 1A (QP paper total 80 vs MS 120).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GlmOcrReconciliationDto(
        @JsonProperty("findings") List<Finding> findings,
        @JsonProperty("qpPaperTotal") Integer qpPaperTotal,
        @JsonProperty("msPaperTotal") Integer msPaperTotal,
        @JsonProperty("paperTotalConflict") boolean paperTotalConflict,
        @JsonProperty("mismatchCount") int mismatchCount) {

    public GlmOcrReconciliationDto {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** Parser finding: severity "match" | "mismatch" | "qp-only" | "ms-only" | "gap". */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(
            @JsonProperty("questionNumber") String questionNumber,
            @JsonProperty("qpMarks") Integer qpMarks,
            @JsonProperty("msMarks") Integer msMarks,
            @JsonProperty("severity") String severity) {
    }

    /** True when any mismatch or paper-total conflict exists → review required. */
    public boolean reviewRequired() {
        return mismatchCount > 0 || paperTotalConflict;
    }
}

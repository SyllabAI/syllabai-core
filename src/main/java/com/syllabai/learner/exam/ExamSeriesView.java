package com.syllabai.learner.exam;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One published sitting in the picker calendar (T-C79). Provenance travels
 * with every row — the hub renders the CORE_MEASURED provenance posture
 * from these fields; {@code estimated = true} rows must render with "≈".
 */
public record ExamSeriesView(
        UUID id,
        String board,
        String qualification,
        String seriesCode,
        String label,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalDate entryDeadline,
        LocalDate resultsDate,
        boolean estimated,
        String sourceUrl,
        Instant retrievedAt) {

    public static final String BOARD_PEARSON_EDEXCEL = "PEARSON_EDEXCEL";

    public static ExamSeriesView from(ExamSeries s) {
        return new ExamSeriesView(s.id(), s.board(), s.qualification(), s.seriesCode(),
                s.label(), s.windowStart(), s.windowEnd(), s.entryDeadline(), s.resultsDate(),
                s.estimated(), s.sourceUrl(), s.retrievedAt());
    }
}

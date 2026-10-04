package com.syllabai.teacher.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The exam-calendar dataset (T-C79, ADR-035 D1 ruling: the calendar enters
 * through the curriculum-import path as REFERENCE data). One dataset covers
 * one board's calendar; every series row carries its own citation — the
 * Pearson source document URL the row was measured from plus the retrieval
 * timestamp. A row without a citation fails closed (anti-fabrication for
 * reference data, ADR-035 Ruling section).
 */
public record ExamSeriesDatasetDto(

        @NotBlank String board,

        @NotNull Instant retrievedAt,

        @NotEmpty List<SeriesRow> series) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SeriesRow(
            @NotNull Qualification qualification,
            @NotBlank String seriesCode,
            @NotBlank String label,
            @NotNull LocalDate windowStart,
            @NotNull LocalDate windowEnd,
            LocalDate entryDeadline,
            LocalDate resultsDate,
            @NotNull Boolean published,
            Boolean estimated,
            @NotBlank String sourceUrl) {

        /**
         * The governed qualification vocabulary for calendar rows (frozen in the
         * importer, not in the DB column — widening is a code change with tests,
         * the package-gate pattern). IAL is the board's own ubiquitous
         * abbreviation for the International Advanced Level.
         */
        public enum Qualification { INTERNATIONAL_GCSE, IAL }
    }
}

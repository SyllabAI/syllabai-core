package com.syllabai.teacher.ingestion;

import com.syllabai.learner.exam.ExamSeries;
import com.syllabai.learner.exam.ExamSeriesRepository;
import com.syllabai.shared.ConflictException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports an exam-calendar dataset into the {@code exam_series} reference
 * table (T-C79, ADR-035 D1 ruling 2026-10-04: the calendar is
 * curriculum-IMPORTED reference data; D2: the ingestion rides the core
 * content-package/curriculum-import path — this service joins
 * {@link CurriculumIngestionService} in the same ingestion package and
 * follows its contract: deterministic, whole-dataset, single transaction,
 * fail-closed validation, provenance mandatory).
 *
 * <p>Fail-closed gates (each one a named anti-fabrication invariant —
 * reference data deserves the same force as content, ADR-035 Ruling):</p>
 * <ol>
 *   <li>the dataset carries a board and a retrieval timestamp;</li>
 *   <li>EVERY row carries a source URL (https) — no citation, no row;</li>
 *   <li>entry deadlines cannot sit after the window starts, results cannot
 *       land before the window ends — a real calendar satisfies both, so a
 *       violation means a transcription error, which fails here;</li>
 *   <li>series codes are kebab-case board-local keys (shape only — the
 *       cadence itself is DATA, never a hardcoded series list);</li>
 *   <li>an UNPUBLISHED row may not claim a published window — estimated
 *       rows exist for "announced but not yet timetabled" and are rendered
 *       with "≈" downstream.</li>
 * </ol>
 *
 * <p>Re-import semantics (measured-calendar reality: boards DO revise
 * windows): identical row = idempotent no-op; a changed row = the measured
 * fields move WITH their new citation (logged) — the import path's whole
 * job is to keep the table faithful to its cited source. The caller sees
 * exactly what happened: imported / updated / unchanged counts.</p>
 */
@Service
public class ExamSeriesImportService {

    private static final Logger log = LoggerFactory.getLogger(ExamSeriesImportService.class);

    private final ExamSeriesRepository examSeries;

    public ExamSeriesImportService(ExamSeriesRepository examSeries) {
        this.examSeries = examSeries;
    }

    public record ImportSummary(int imported, int updated, int unchanged) {
    }

    @Transactional
    public ImportSummary importDataset(ExamSeriesDatasetDto dataset) {
        if (dataset.board() == null || dataset.board().isBlank()) {
            throw new ConflictException("exam-series dataset must name its board");
        }
        if (dataset.retrievedAt() == null) {
            throw new ConflictException("exam-series dataset must carry retrievedAt");
        }
        Instant now = Instant.now();
        int imported = 0;
        int updated = 0;
        int unchanged = 0;

        for (ExamSeriesDatasetDto.SeriesRow row : dataset.series()) {
            validateRow(dataset, row);

            var existing = examSeries.findByBoardAndQualificationAndSeriesCode(
                    dataset.board(), row.qualification().name(), row.seriesCode());
            if (existing.isEmpty()) {
                examSeries.save(ExamSeries.newImported(
                        UUID.randomUUID(), dataset.board(), row.qualification().name(),
                        row.seriesCode(), row.label(), row.windowStart(), row.windowEnd(),
                        row.entryDeadline(), row.resultsDate(), row.published(),
                        Boolean.TRUE.equals(row.estimated()), row.sourceUrl(),
                        dataset.retrievedAt(), now));
                imported++;
            } else if (sameMeasurement(existing.get(), row)) {
                unchanged++;
            } else {
                ExamSeries series = existing.get();
                series.applyImport(row.label(), row.windowStart(), row.windowEnd(),
                        row.entryDeadline(), row.resultsDate(), row.published(),
                        Boolean.TRUE.equals(row.estimated()), row.sourceUrl(),
                        dataset.retrievedAt(), now);
                examSeries.save(series);
                log.info("exam-series calendar correction: {} {} moved to its newer citation {}",
                        series.qualification(), series.seriesCode(), row.sourceUrl());
                updated++;
            }
        }
        return new ImportSummary(imported, updated, unchanged);
    }

    private void validateRow(ExamSeriesDatasetDto dataset, ExamSeriesDatasetDto.SeriesRow row) {
        if (row.seriesCode() == null || !row.seriesCode().matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new ConflictException(
                    "exam-series seriesCode must be a kebab-case key: " + row.seriesCode());
        }
        if (row.windowEnd().isBefore(row.windowStart())) {
            throw new ConflictException(
                    "exam-series window_end before window_start: " + row.seriesCode());
        }
        // a real calendar satisfies both; a violation is a transcription error — fail closed
        if (row.entryDeadline() != null && row.entryDeadline().isAfter(row.windowStart())) {
            throw new ConflictException(
                    "exam-series entry deadline after window start: " + row.seriesCode());
        }
        if (row.resultsDate() != null && row.resultsDate().isBefore(row.windowEnd())) {
            throw new ConflictException(
                    "exam-series results date before window end: " + row.seriesCode());
        }
        if (row.sourceUrl() == null || !row.sourceUrl().startsWith("https://")) {
            throw new ConflictException(
                    "exam-series row without an https citation fails closed: " + row.seriesCode());
        }
        if (!row.published() && Boolean.TRUE.equals(row.estimated()) && row.windowStart() == null) {
            // structural honesty: an estimated row still needs a window to be honest about
            throw new ConflictException("estimated row without a window: " + row.seriesCode());
        }
        if (!row.published() && !Boolean.TRUE.equals(row.estimated())) {
            throw new ConflictException(
                    "unpublished non-estimated rows are not importable: " + row.seriesCode());
        }
    }

    private boolean sameMeasurement(ExamSeries series, ExamSeriesDatasetDto.SeriesRow row) {
        return series.label().equals(row.label())
                && series.windowStart().equals(row.windowStart())
                && series.windowEnd().equals(row.windowEnd())
                && java.util.Objects.equals(series.entryDeadline(), row.entryDeadline())
                && java.util.Objects.equals(series.resultsDate(), row.resultsDate())
                && series.published() == row.published()
                && series.estimated() == Boolean.TRUE.equals(row.estimated())
                && series.sourceUrl().equals(row.sourceUrl());
    }
}

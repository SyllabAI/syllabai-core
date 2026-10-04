package com.syllabai.teacher.ingestion;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The exam-calendar import endpoint (T-C79, ADR-035 D1 ruling: the calendar
 * is curriculum-IMPORTED reference data — this is the import path the ruling
 * named, in the same ingestion package as the curriculum draft importer).
 * Teacher/Admin only (the ContentController precedent): learners never write
 * calendar rows — they only ever PICK from what this path imports.
 *
 * <p>Fail-closed contract lives in {@link ExamSeriesImportService}: no row
 * without an https citation, no deadline-after-window nonsense, no silent
 * non-published rows. Re-importing a corrected calendar (boards do revise
 * windows) moves the measured fields WITH their newer citation, logged.</p>
 */
@RestController
@RequestMapping("/api/v1/teacher/curriculum")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class TeacherExamSeriesImportController {

    private final ExamSeriesImportService imports;

    public TeacherExamSeriesImportController(ExamSeriesImportService imports) {
        this.imports = imports;
    }

    @PostMapping("/exam-series")
    public ExamSeriesImportService.ImportSummary importExamSeries(
            @RequestBody ExamSeriesDatasetDto dataset) {
        return imports.importDataset(dataset);
    }
}

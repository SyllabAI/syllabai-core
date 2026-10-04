package com.syllabai.learner.exam;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * One declared exam target with its countdown, derived at READ on the server
 * clock (T-C79, ADR-031 doctrine: derived is recomputed, never stored —
 * there is no days_to_window column anywhere). A negative
 * {@code daysToWindowStart} means the window has opened; the honest
 * "entries closed" fact is a plain comparison the client can restate from
 * {@code entryDeadlinePassed}.
 *
 * <p>Day arithmetic is on UTC calendar dates (the window dates are
 * board-published calendar dates, not instants); the hub renders
 * day-granularity only. Estimated rows carry {@code estimated = true} and
 * MUST render with "≈" — never as fact.</p>
 */
public record CourseExamTargetView(
        String courseSlug,
        UUID seriesId,
        String seriesCode,
        String label,
        LocalDate windowStart,
        LocalDate windowEnd,
        LocalDate entryDeadline,
        LocalDate resultsDate,
        boolean estimated,
        long daysToWindowStart,
        long daysToWindowEnd,
        boolean entryDeadlinePassed) {

    public static CourseExamTargetView of(LearnerCourseEnrolment enrolment, ExamSeries series,
                                          LocalDate today) {
        return new CourseExamTargetView(
                enrolment.courseSlug(),
                series.id(),
                series.seriesCode(),
                series.label(),
                series.windowStart(),
                series.windowEnd(),
                series.entryDeadline(),
                series.resultsDate(),
                series.estimated(),
                ChronoUnit.DAYS.between(today, series.windowStart()),
                ChronoUnit.DAYS.between(today, series.windowEnd()),
                series.entryDeadline() != null && series.entryDeadline().isBefore(today));
    }
}

package com.syllabai.learner.exam;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The shared read for declared exam targets (T-C79): every course the
 * learner gave a target series to, with the countdown derived on THIS read
 * against the server clock (ADR-031 — derived is recomputed, never stored).
 * The learner-state and agenda read models compose this block; an empty
 * list is the honest "no series declared" state, never an invented date.
 */
@Service
public class ExamTargetReader {

    private final LearnerCourseEnrolmentRepository enrolments;
    private final ExamSeriesRepository examSeries;

    public ExamTargetReader(LearnerCourseEnrolmentRepository enrolments,
                            ExamSeriesRepository examSeries) {
        this.enrolments = enrolments;
        this.examSeries = examSeries;
    }

    public List<CourseExamTargetView> targetsFor(UUID learnerId) {
        return targetsFor(learnerId, LocalDate.now(ZoneOffset.UTC));
    }

    public List<CourseExamTargetView> targetsFor(UUID learnerId, LocalDate today) {
        List<LearnerCourseEnrolment> declared =
                enrolments.findByLearnerIdAndTargetSeriesIdNotNull(learnerId);
        if (declared.isEmpty()) {
            return List.of();
        }
        Set<UUID> seriesIds = new HashSet<>();
        declared.forEach(e -> seriesIds.add(e.targetSeriesId()));
        Map<UUID, ExamSeries> series = examSeries.findAllById(seriesIds).stream()
                .collect(Collectors.toMap(ExamSeries::id, Function.identity(), (a, b) -> a));
        return declared.stream()
                .filter(e -> series.containsKey(e.targetSeriesId()))
                .map(e -> CourseExamTargetView.of(e, series.get(e.targetSeriesId()), today))
                .toList();
    }
}

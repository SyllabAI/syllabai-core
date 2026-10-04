package com.syllabai.learner.exam;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExamSeriesRepository extends JpaRepository<ExamSeries, UUID> {

    /** the picker list: published sittings of a qualification, soonest first */
    List<ExamSeries> findByBoardAndQualificationAndPublishedOrderByWindowStartAsc(
            String board, String qualification, boolean published);

    /** the whole published calendar (picker without a qualification filter) */
    List<ExamSeries> findByPublishedOrderByWindowStartAsc(boolean published);

    Optional<ExamSeries> findByBoardAndQualificationAndSeriesCode(
            String board, String qualification, String seriesCode);
}

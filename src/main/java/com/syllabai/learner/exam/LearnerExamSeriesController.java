package com.syllabai.learner.exam;

import com.syllabai.identity.CurrentUserId;
import com.syllabai.shared.BadRequestException;
import com.syllabai.shared.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The learner's exam-series surface (T-C79, ADR-035 D1 ruling 2026-10-04):
 * the picker over the IMPORTED calendar plus the one bounded, optional,
 * editable, clearable target-series declaration per enrolled course. The
 * learner picks an id — they never type a date (ADR-035 rejected ③ stands;
 * there is no learner-writable date field anywhere in this contract).
 *
 * <p>Routes (all under /api/v1/learners/me — identity from the JWT, the
 * CourseStats convention):</p>
 * <ul>
 *   <li>GET /exam-series — the published calendar (optionally filtered by
 *       qualification) that powers the hub add-course picker; an empty
 *       calendar is the honest empty state, never a fake list;</li>
 *   <li>PUT /courses/{slug}/target-series — declare/correct the target
 *       (idempotent upsert of the enrolment row);</li>
 *   <li>DELETE /courses/{slug}/target-series — clear the target ("not sure
 *       yet" stays a first-class state; the enrolment row remains).</li>
 * </ul>
 *
 * <p>Countdowns ({@code daysToWindowStart}/{@code daysToWindowEnd},
 * {@code entryDeadlinePassed}) are computed HERE, at read, on the server
 * clock — derived is recomputed, never stored (ADR-031). Nothing persists.</p>
 */
@RestController
@RequestMapping("/api/v1/learners/me")
public class LearnerExamSeriesController {

    private final ExamSeriesRepository examSeries;
    private final LearnerCourseEnrolmentRepository enrolments;

    public LearnerExamSeriesController(ExamSeriesRepository examSeries,
                                       LearnerCourseEnrolmentRepository enrolments) {
        this.examSeries = examSeries;
        this.enrolments = enrolments;
    }

    /** the picker list — published sittings only, soonest window first */
    @GetMapping("/exam-series")
    public List<ExamSeriesView> calendar(
            @CurrentUserId UUID learnerId,
            @RequestParam(required = false) String qualification) {
        List<ExamSeries> rows = (qualification == null || qualification.isBlank())
                ? examSeries.findByPublishedOrderByWindowStartAsc(true)
                : examSeries.findByBoardAndQualificationAndPublishedOrderByWindowStartAsc(
                        ExamSeriesView.BOARD_PEARSON_EDEXCEL, qualification, true);
        return rows.stream().map(ExamSeriesView::from).toList();
    }

    /** declare or correct the target series for one enrolled course */
    @PutMapping("/courses/{courseSlug}/target-series")
    public CourseExamTargetView setTarget(
            @CurrentUserId UUID learnerId,
            @PathVariable String courseSlug,
            @Valid @RequestBody SetTargetRequest request) {
        String slug = validatedSlug(courseSlug);
        ExamSeries series = examSeries.findById(request.seriesId())
                .orElseThrow(() -> new NotFoundException(
                        "exam series " + request.seriesId() + " does not exist"));
        if (!series.published()) {
            throw new BadRequestException(
                    "only published exam series can be targeted: " + series.seriesCode());
        }
        var enrolment = enrolments.findByLearnerIdAndCourseSlug(learnerId, slug)
                .orElseGet(() -> new LearnerCourseEnrolment(UUID.randomUUID(), learnerId, slug,
                        null, java.time.Instant.now()));
        enrolment.retarget(series.id(), java.time.Instant.now());
        enrolments.save(enrolment);
        return CourseExamTargetView.of(enrolment, series, today());
    }

    /** clear the target — "not sure yet" is honest; the enrolment row remains */
    @DeleteMapping("/courses/{courseSlug}/target-series")
    public ResponseEntity<Void> clearTarget(
            @CurrentUserId UUID learnerId, @PathVariable String courseSlug) {
        String slug = validatedSlug(courseSlug);
        enrolments.findByLearnerIdAndCourseSlug(learnerId, slug).ifPresent(e -> {
            e.retarget(null, java.time.Instant.now());
            enrolments.save(e);
        });
        return ResponseEntity.noContent().build();
    }

    /**
     * The read-model block shared with /state and /agenda lives in
     * {@link ExamTargetReader} — every course this learner declared a target
     * for, with the countdown derived on this read (never stored). Absent
     * entries ARE the honest "no series declared" state — clients render
     * "add your exam series", never an invented date.
     */

    public record SetTargetRequest(@NotNull UUID seriesId) {
    }

    private static String validatedSlug(String courseSlug) {
        if (courseSlug == null || !courseSlug.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new BadRequestException("course slug must be a kebab-case registry key");
        }
        return courseSlug;
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }
}

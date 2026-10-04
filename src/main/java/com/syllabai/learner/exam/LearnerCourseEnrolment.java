package com.syllabai.learner.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The learner-course enrolment binding (T-C79, ADR-035 D1 ruling): ONE
 * optional, bounded declaration — which imported exam series a course
 * targets. UNIQUE (learner_id, course_slug) IS the "at most one target
 * series per enrolment" constraint; {@code targetSeriesId} is nullable
 * because "Not sure yet" is a first-class honest state, and clearing the
 * target keeps the enrolment row (the declaration is editable and
 * clearable — the bounded-wrong-pick property the ruling named).
 *
 * <p>This row is a REFERENCE, never a date: the learner picks an imported
 * series id; no learner-entered date/window/timetable exists anywhere
 * (ADR-035 rejected ③, standing). course_slug is the hub registry lane slug
 * (content/courses.json) — core validates shape only and stays
 * registry-agnostic.</p>
 */
@Entity
@Table(name = "learner_course_enrolments")
public class LearnerCourseEnrolment {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "course_slug", nullable = false, length = 100)
    private String courseSlug;

    @Column(name = "target_series_id")
    private UUID targetSeriesId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LearnerCourseEnrolment() {
    }

    public LearnerCourseEnrolment(UUID id, UUID learnerId, String courseSlug,
                                  UUID targetSeriesId, Instant now) {
        this.id = id;
        this.learnerId = learnerId;
        this.courseSlug = courseSlug;
        this.targetSeriesId = targetSeriesId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void retarget(UUID seriesId, Instant now) {
        this.targetSeriesId = seriesId;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID learnerId() {
        return learnerId;
    }

    public String courseSlug() {
        return courseSlug;
    }

    public UUID targetSeriesId() {
        return targetSeriesId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

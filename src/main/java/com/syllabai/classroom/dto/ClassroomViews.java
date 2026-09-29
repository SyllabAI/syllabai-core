package com.syllabai.classroom.dto;

import com.syllabai.classroom.Announcement;
import com.syllabai.classroom.SchoolClass;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire views for the classroom foundation (V51). Records only — the module
 * has no domain logic beyond entity lifecycle, so one file mirrors the
 * assignment module's {@code AssignmentViews} pattern.
 */
public final class ClassroomViews {

    private ClassroomViews() {
    }

    /** teacher list row: the class + live member count */
    public record TeacherClassView(
            UUID id,
            String courseSlug,
            String courseLabel,
            String name,
            String status,
            long memberCount,
            Instant createdAt) {

        public static TeacherClassView of(SchoolClass c, long memberCount) {
            return new TeacherClassView(c.id(), c.courseSlug(), c.courseLabel(),
                    c.name(), c.status().wire(), memberCount, c.createdAt());
        }
    }

    /** one roster row: the student + when they were enrolled + by whom */
    public record ClassMemberView(
            UUID studentId,
            String displayName,
            String email,
            UUID enrolledBy,
            Instant enrolledAt) {
    }

    /** class detail: the class + its full roster */
    public record TeacherClassDetailView(
            UUID id,
            String courseSlug,
            String courseLabel,
            String name,
            String status,
            Instant createdAt,
            List<ClassMemberView> members) {
    }

    /** teacher announcement row: content + read-state count over the roster */
    public record TeacherAnnouncementView(
            UUID id,
            String title,
            String body,
            String category,
            long readCount,
            long memberCount,
            Instant createdAt) {
    }

    /** learner-side class row (the classroom overlay): the class + unread count */
    public record LearnerClassView(
            UUID id,
            String courseSlug,
            String courseLabel,
            String name,
            String teacherName,
            long unreadAnnouncements,
            Instant enrolledAt) {
    }

    /** learner announcement row: content + whether THIS student has read it */
    public record LearnerAnnouncementView(
            UUID id,
            UUID classId,
            String className,
            String courseSlug,
            String teacherName,
            String title,
            String body,
            String category,
            boolean read,
            Instant createdAt) {

        public static LearnerAnnouncementView of(Announcement a, String className,
                                                 String courseSlug, String teacherName,
                                                 boolean read) {
            return new LearnerAnnouncementView(a.id(), a.classId(), className,
                    courseSlug, teacherName, a.title(), a.body(),
                    a.category().wire(), read, a.createdAt());
        }
    }

    /** learner classroom overview: my classes + the flattened unread badge */
    public record LearnerClassroomView(
            List<LearnerClassView> classes,
            long unreadAnnouncements) {
    }
}

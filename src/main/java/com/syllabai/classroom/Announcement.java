package com.syllabai.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A teacher announcement to one class (V51; TEACHER_ARCHITECTURE §9) —
 * teacher-to-class communication, explicitly NOT an AI channel: no LLM path
 * touches this module.
 *
 * <p>The row is immutable once published (edit/delete is subject to the §9
 * audit policy and is deliberately NOT in the foundation slice — the teacher
 * surface says so honestly). Student read state lives in
 * {@link AnnouncementRead}, so this row never mutates on a read.</p>
 */
@Entity
@Table(name = "announcements")
public class Announcement {

    public enum Category {
        GENERAL, HOMEWORK, NOTICE, EXAM_REMINDER, RESOURCE;

        /** tolerant parse for the hub's wire forms ("exam-reminder" etc.) */
        public static Category parse(String raw) {
            if (raw == null || raw.isBlank()) return GENERAL;
            return switch (raw.trim().toLowerCase().replace('-', '_')) {
                case "general" -> GENERAL;
                case "homework" -> HOMEWORK;
                case "notice" -> NOTICE;
                case "exam_reminder" -> EXAM_REMINDER;
                case "resource" -> RESOURCE;
                default -> null;
            };
        }

        /** canonical wire form served to clients (kebab-case) */
        public String wire() {
            return switch (this) {
                case GENERAL -> "general";
                case HOMEWORK -> "homework";
                case NOTICE -> "notice";
                case EXAM_REMINDER -> "exam-reminder";
                case RESOURCE -> "resource";
            };
        }
    }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Announcement() {
        // JPA
    }

    public Announcement(UUID teacherId, UUID classId, String title, String body, Category category) {
        this.teacherId = teacherId;
        this.classId = classId;
        this.title = title;
        this.body = body;
        this.category = category.name();
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID teacherId() { return teacherId; }
    public UUID classId() { return classId; }
    public String title() { return title; }
    public String body() { return body; }
    public Category category() { return Category.valueOf(category); }
    public Instant createdAt() { return createdAt; }
}

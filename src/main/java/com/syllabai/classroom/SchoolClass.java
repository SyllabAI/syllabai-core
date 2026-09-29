package com.syllabai.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A teacher-owned class on one hub course — the explicit organizational
 * boundary TFA-01/TFA-02 introduce (TEACHER_ARCHITECTURE.md §8; V51). Named
 * {@code SchoolClass} because {@code Class} collides with the language.
 *
 * <p>Design rules (mirroring the V47/V48/V49 discipline):</p>
 * <ul>
 *   <li>COURSE REFS ARE OPAQUE, HUB-OWNED: {@code courseSlug} /
 *       {@code courseLabel} are the hub's course identity. Core has no
 *       course registry and deliberately does not pretend to; the hub
 *       renders the full curriculum context from its own registry, and core
 *       authorizes on class OWNERSHIP (teacher_id) — the server-side gate
 *       TEACHER_ARCHITECTURE §17 mandates.</li>
 *   <li>A class is workflow state, not evidence: creating, enrolling,
 *       publishing or archiving writes no SkillState / BKT / misconception /
 *       review rows. Pinned by ClassroomFlowIT.</li>
 *   <li>NOT teaching coverage: taught-vs-not-taught is TFA-03 and must not
 *       be inferred from any row in this module.</li>
 * </ul>
 */
@Entity
@Table(name = "classes")
public class SchoolClass {

    public enum Status {
        ACTIVE, ARCHIVED;

        /** tolerant parse for the hub's wire forms ("active" / "archived") */
        public static Status parse(String raw) {
            if (raw == null) return null;
            return switch (raw.trim().toLowerCase()) {
                case "active" -> ACTIVE;
                case "archived" -> ARCHIVED;
                default -> null;
            };
        }

        /** canonical wire form served to clients */
        public String wire() {
            return this == ACTIVE ? "active" : "archived";
        }
    }

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "teacher_id", nullable = false)
    private UUID teacherId;

    /** hub course slug (e.g. "igcse-chemistry-19") — opaque external reference */
    @Column(name = "course_slug", nullable = false, length = 64)
    private String courseSlug;

    /** hub course display label (e.g. "IGCSE Chemistry") — opaque external reference */
    @Column(name = "course_label", nullable = false, length = 120)
    private String courseLabel;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SchoolClass() {
        // JPA
    }

    public SchoolClass(UUID teacherId, String courseSlug, String courseLabel, String name) {
        this.teacherId = teacherId;
        this.courseSlug = courseSlug;
        this.courseLabel = courseLabel;
        this.name = name;
        this.status = Status.ACTIVE.name();
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID teacherId() { return teacherId; }
    public String courseSlug() { return courseSlug; }
    public String courseLabel() { return courseLabel; }
    public String name() { return name; }
    public Status status() { return Status.valueOf(status); }
    public void status(Status status) { this.status = status.name(); }
    public Instant createdAt() { return createdAt; }
}

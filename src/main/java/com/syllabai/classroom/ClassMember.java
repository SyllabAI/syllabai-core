package com.syllabai.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One explicit enrollment of a student into a class (V51; TEACHER_ARCHITECTURE
 * §8). This row replaces the V49 pilot shortcut — "the class is the enabled
 * STUDENT set" — wherever a class target is chosen, and it is the ONLY source
 * of classroom visibility on the learner side: no membership row, no classroom
 * capability (the independent-student rule).
 *
 * <p>Enrollment is workflow state, not learner-model evidence: inserting or
 * deleting a row here must never touch BKT/SkillState/misconception/review
 * state (pinned by ClassroomFlowIT). The UNIQUE (class_id, student_id) pair
 * makes re-enrollment idempotent at the storage layer; the service treats a
 * duplicate as the honest no-op it is.</p>
 */
@Entity
@Table(name = "class_members")
public class ClassMember {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(name = "enrolled_by", nullable = false)
    private UUID enrolledBy;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    protected ClassMember() {
        // JPA
    }

    public ClassMember(UUID classId, UUID studentId, UUID enrolledBy) {
        this.classId = classId;
        this.studentId = studentId;
        this.enrolledBy = enrolledBy;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (enrolledAt == null) enrolledAt = Instant.now();
    }

    public UUID id() { return id; }
    public UUID classId() { return classId; }
    public UUID studentId() { return studentId; }
    public UUID enrolledBy() { return enrolledBy; }
    public Instant enrolledAt() { return enrolledAt; }
}

package com.syllabai.classroom;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One student's read receipt for one announcement (V51; TEACHER_ARCHITECTURE
 * §9 "student read state"). Composite-keyed, append-only: marking an
 * announcement read twice is the same state, so the service treats a repeat
 * as an idempotent no-op. The announcement row itself never mutates.
 */
@Entity
@Table(name = "announcement_reads")
@IdClass(AnnouncementRead.Pk.class)
public class AnnouncementRead implements Serializable {

    private static final long serialVersionUID = 1L;

    /** composite primary key (announcement_id, student_id) */
    public static class Pk implements Serializable {

        private static final long serialVersionUID = 1L;

        private UUID announcementId;
        private UUID studentId;

        public Pk() {
            // JPA
        }

        public Pk(UUID announcementId, UUID studentId) {
            this.announcementId = announcementId;
            this.studentId = studentId;
        }

        public UUID getAnnouncementId() { return announcementId; }
        public UUID getStudentId() { return studentId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(announcementId, pk.announcementId)
                    && Objects.equals(studentId, pk.studentId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(announcementId, studentId);
        }
    }

    @Id
    @Column(name = "announcement_id")
    private UUID announcementId;

    @Id
    @Column(name = "student_id")
    private UUID studentId;

    @Column(name = "read_at", nullable = false)
    private Instant readAt;

    protected AnnouncementRead() {
        // JPA
    }

    public AnnouncementRead(UUID announcementId, UUID studentId) {
        this.announcementId = announcementId;
        this.studentId = studentId;
    }

    @jakarta.persistence.PrePersist
    void onInsert() {
        if (readAt == null) readAt = Instant.now();
    }

    public UUID announcementId() { return announcementId; }
    public UUID studentId() { return studentId; }
    public Instant readAt() { return readAt; }
}

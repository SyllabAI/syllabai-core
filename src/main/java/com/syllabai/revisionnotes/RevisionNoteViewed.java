package com.syllabai.revisionnotes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One learner's "viewed" marker for a revision note (the Save-My-Exams-style
 * progress model: opening a note marks it viewed; the per-subtopic ring is
 * viewed notes ÷ total notes). Writing is idempotent per (user, note) — the
 * unique constraint plus the service's existence check make double-posting a
 * no-op, not a conflict.
 */
@Entity
@Table(name = "revision_note_viewed")
public class RevisionNoteViewed {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "note_id", nullable = false, length = 256)
    private String noteId;

    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;

    protected RevisionNoteViewed() {
    }

    public RevisionNoteViewed(UUID userId, String noteId, Instant viewedAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.noteId = noteId;
        this.viewedAt = viewedAt;
    }

    @PrePersist
    void stamp() {
        if (viewedAt == null) viewedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String noteId() {
        return noteId;
    }

    public Instant viewedAt() {
        return viewedAt;
    }
}

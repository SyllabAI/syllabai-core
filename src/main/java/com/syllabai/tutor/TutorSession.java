package com.syllabai.tutor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One Tutor chat (s140 — the Spec §22 {@code tutor/sessions} substrate).
 *
 * <p>The session is learner-owned and lifecycle-light: no title, no status —
 * "new chat" simply starts a new session row, and a session is "the latest"
 * precisely until a newer one exists. Turns live in
 * {@link TutorSessionTurn}; this row only anchors ownership and recency.</p>
 *
 * <p>Privacy: this is the only sanctioned server-side transcript surface
 * (Master Spec §22). It stores the learner's own tutor chat and nothing
 * else; retrieval and append both fail with an indistinguishable 404 for any
 * session the caller does not own.</p>
 */
@Entity
@Table(name = "tutor_sessions")
public class TutorSession {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** bumped on every appended turn — the recency key for hydration */
    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt;

    protected TutorSession() {
        // JPA
    }

    public TutorSession(UUID learnerId, Instant now) {
        this.learnerId = learnerId;
        this.createdAt = now;
        this.lastActiveAt = now;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
    }

    /** append lifecycle hook: keep recency in lockstep with the turns */
    void markActive(Instant at) {
        if (at != null && (lastActiveAt == null || at.isAfter(lastActiveAt))) {
            lastActiveAt = at;
        }
    }

    public UUID id() { return id; }
    public UUID learnerId() { return learnerId; }
    public Instant createdAt() { return createdAt; }
    public Instant lastActiveAt() { return lastActiveAt; }
}

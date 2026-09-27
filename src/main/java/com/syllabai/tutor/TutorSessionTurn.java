package com.syllabai.tutor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One turn of a {@link TutorSession} (s140, Spec §22 transcript store).
 *
 * <p>Append-only: a turn is written once (the user question and the tutor's
 * answer land together after the pipeline completes — a failed ask stores
 * nothing, matching the s139 decision that error bubbles are UI chrome, not
 * conversation). The {@code seq} is per-session, 1-based, gap-free.</p>
 *
 * <p>Assistant turns store the answer with citation markers removed
 * ({@link ConversationTurn} sanitization — {@code [n]} numbers belong to
 * SOURCES absent from any later rendering) plus the §19 traceability fields
 * so a hydrated transcript shows the same honest footer the live answer
 * showed. The citation archive of record stays the immutable
 * KA_RAG_COMPLETED telemetry row.</p>
 */
@Entity
@Table(name = "tutor_session_turns")
public class TutorSessionTurn {

    public static final int MAX_CONTENT_CHARS = 4000;

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "seq", nullable = false)
    private int seq;

    /** USER or ASSISTANT — mirrors {@link ConversationTurn} roles exactly */
    @Column(name = "role", nullable = false, length = 8)
    private String role;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Column(name = "refused", nullable = false)
    private boolean refused;

    @Column(name = "answer_model", length = 120)
    private String answerModel;

    @Column(name = "answer_provider", length = 60)
    private String answerProvider;

    @Column(name = "latency_ms")
    private Double latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TutorSessionTurn() {
        // JPA
    }

    public TutorSessionTurn(UUID sessionId, int seq, String role, String content,
                            int evidenceCount, boolean refused, String answerModel,
                            String answerProvider, Double latencyMs, Instant createdAt) {
        this.sessionId = sessionId;
        this.seq = seq;
        this.role = role;
        this.content = content == null ? "" : content;
        this.evidenceCount = evidenceCount;
        this.refused = refused;
        this.answerModel = answerModel;
        this.answerProvider = answerProvider;
        this.latencyMs = latencyMs;
        this.createdAt = createdAt;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID id() { return id; }
    public UUID sessionId() { return sessionId; }
    public int seq() { return seq; }
    public String role() { return role; }
    public String content() { return content; }
    public int evidenceCount() { return evidenceCount; }
    public boolean refused() { return refused; }
    public String answerModel() { return answerModel; }
    public String answerProvider() { return answerProvider; }
    public Double latencyMs() { return latencyMs; }
    public Instant createdAt() { return createdAt; }
}

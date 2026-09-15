package com.syllabai.intervention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "intervention_run_evidence")
public class InterventionRunEvidence {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "evidence_ref", nullable = false, length = 512)
    private String evidenceRef;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    protected InterventionRunEvidence() { }

    public InterventionRunEvidence(UUID runId, String evidenceRef, String role, Instant capturedAt) {
        if (runId == null) throw new IllegalArgumentException("runId is required");
        if (evidenceRef == null || evidenceRef.isBlank()) throw new IllegalArgumentException("evidenceRef is required");
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role is required");
        this.runId = runId;
        this.evidenceRef = evidenceRef;
        this.role = role;
        this.capturedAt = capturedAt == null ? Instant.now() : capturedAt;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
    }

    public UUID id() { return id; }
    public UUID runId() { return runId; }
    public String evidenceRef() { return evidenceRef; }
    public String role() { return role; }
    public Instant capturedAt() { return capturedAt; }
}

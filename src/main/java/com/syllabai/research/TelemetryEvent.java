package com.syllabai.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only learning-log event (Master Spec §18; immutable research record). */
@Entity
@Table(name = "telemetry_events")
public class TelemetryEvent {
    public enum Type {
        ATTEMPT_SUBMITTED, BKT_UPDATED, BDT_UPDATED, REVIEW_SCHEDULED, DECAY_APPLIED,
        SELF_DOUBT_FLAGGED, SMART_MARK_COMPLETED, HUMAN_MARK_RECORDED, KA_RAG_COMPLETED,
        STRUGGLE_INFERRED, TUTOR_INTERVENTION_SELECTED
    }

    @Id @Column(name = "id") private UUID id;
    @Column(name = "learner_id", nullable = false) private UUID learnerId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false, length = 40) private Type type;
    @Column(name = "schema_version", nullable = false) private int schemaVersion = 1;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;

    protected TelemetryEvent() {}
    public TelemetryEvent(UUID learnerId, Type type, Map<String, Object> payload, Instant occurredAt) {
        this.learnerId = learnerId; this.type = type; this.payload = Map.copyOf(payload); this.occurredAt = occurredAt;
    }
    @PrePersist void onInsert() { if (id == null) id = UUID.randomUUID(); }
    public UUID id() { return id; }
    public UUID learnerId() { return learnerId; }
    public Type type() { return type; }
    public int schemaVersion() { return schemaVersion; }
    public Map<String, Object> payload() { return payload; }
    public Instant occurredAt() { return occurredAt; }
}

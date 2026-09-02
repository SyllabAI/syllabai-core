package com.syllabai.research;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Versioned model-parameter registry (Master Spec §19 reproducibility: research
 * experiments must record provider/model/parameter versions). Seeded by migration
 * V5/V6 with the Paper B Cycle-1 parameters.
 */
@Entity
@Table(name = "model_versions",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uq_model_version", columnNames = {"registry_key", "version"}))
public class ModelVersion {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "registry_key", nullable = false, length = 60)
    private String registryKey;   // e.g. learner.bkt, learner.bdt, learner.decay

    @Column(name = "version", nullable = false, length = 30)
    private String version;       // e.g. v1-cycle1

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(name = "provenance", length = 300)
    private String provenance;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ModelVersion() {
        // JPA
    }

    public ModelVersion(String registryKey, String version, Map<String, Object> params,
                        String provenance, String notes) {
        this.registryKey = registryKey;
        this.version = version;
        this.params = Map.copyOf(params);
        this.provenance = provenance;
        this.notes = notes;
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public String registryKey() { return registryKey; }
    public String version() { return version; }
    public Map<String, Object> params() { return params; }
    public String provenance() { return provenance; }
    public String notes() { return notes; }
    public Instant createdAt() { return createdAt; }
}

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

/**
 * Research experiment registry (Master Spec §19, §36; V5 table {@code experiments}).
 * A {@code RUNNING} experiment with a pinned provider is served by exactly that
 * provider — never the failover chain (§26.1). Rows are managed through migrations
 * or the future experiment-admin workflow; the LLM chain only reads them.
 */
@Entity
@Table(name = "experiments")
public class Experiment {

    public enum Status { DRAFT, RUNNING, PAUSED, COMPLETED, ARCHIVED }

    @Id
    @Column(name = "id")
    private UUID id;

    /** stable experiment identifier used by {@code LlmRequest.experimentId} */
    @Column(name = "experiment_key", nullable = false, length = 60)
    private String experimentKey;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "hypothesis", columnDefinition = "text")
    private String hypothesis;

    /** provider name the experiment is pinned to, e.g. "groq" */
    @Column(name = "pinned_provider", length = 30)
    private String pinnedProvider;

    /** optional model override for the pinned provider */
    @Column(name = "pinned_model", length = 100)
    private String pinnedModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params", columnDefinition = "jsonb")
    private Map<String, Object> params;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Experiment() {
        // JPA
    }

    public Experiment(String experimentKey, String name, String hypothesis,
                      String pinnedProvider, String pinnedModel, Status status,
                      Map<String, Object> params) {
        this.experimentKey = experimentKey;
        this.name = name;
        this.hypothesis = hypothesis;
        this.pinnedProvider = pinnedProvider;
        this.pinnedModel = pinnedModel;
        this.status = status;
        this.params = params == null ? null : Map.copyOf(params);
    }

    @PrePersist
    void onInsert() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }

    public UUID id() { return id; }
    public String experimentKey() { return experimentKey; }
    public String name() { return name; }
    public String hypothesis() { return hypothesis; }
    public String pinnedProvider() { return pinnedProvider; }
    public String pinnedModel() { return pinnedModel; }
    public Status status() { return status; }
    public Map<String, Object> params() { return params; }
    public Instant createdAt() { return createdAt; }
}

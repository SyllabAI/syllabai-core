-- V5: research — append-only telemetry, model/prompt/experiment registries
-- (Master Spec §18, §19, §36)

CREATE TABLE telemetry_events (
    id             UUID PRIMARY KEY,
    learner_id     UUID NOT NULL,
    event_type     VARCHAR(40) NOT NULL
        CONSTRAINT ck_telemetry_type CHECK (event_type IN
            ('ATTEMPT_SUBMITTED', 'BKT_UPDATED', 'BDT_UPDATED',
             'REVIEW_SCHEDULED', 'DECAY_APPLIED', 'SELF_DOUBT_FLAGGED')),
    schema_version INT NOT NULL DEFAULT 1,
    payload        JSONB NOT NULL,
    occurred_at    TIMESTAMPTZ NOT NULL
);

-- append-only research data: no updates, no deletes (enforced by policy + triggers)
CREATE INDEX ix_telemetry_learner_time ON telemetry_events (learner_id, occurred_at DESC);
CREATE INDEX ix_telemetry_type ON telemetry_events (event_type);

CREATE TABLE model_versions (
    id           UUID PRIMARY KEY,
    registry_key VARCHAR(60) NOT NULL,
    version      VARCHAR(30) NOT NULL,
    params       JSONB NOT NULL,
    provenance   VARCHAR(300),
    notes        VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_model_version UNIQUE (registry_key, version)
);

CREATE TABLE prompt_versions (
    id           UUID PRIMARY KEY,
    registry_key VARCHAR(60) NOT NULL,
    version      VARCHAR(30) NOT NULL,
    template     TEXT NOT NULL,
    notes        VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_prompt_version UNIQUE (registry_key, version)
);

CREATE TABLE experiments (
    id              UUID PRIMARY KEY,
    experiment_key  VARCHAR(60) NOT NULL UNIQUE,
    name            VARCHAR(200) NOT NULL,
    hypothesis      TEXT,
    pinned_provider VARCHAR(30),
    pinned_model    VARCHAR(100),
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT ck_experiment_status CHECK (status IN
            ('DRAFT', 'RUNNING', 'PAUSED', 'COMPLETED', 'ARCHIVED')),
    params          JSONB,
    created_at      TIMESTAMPTZ NOT NULL
);

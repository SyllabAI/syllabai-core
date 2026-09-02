-- V4: learner state — BKT mastery, BDT misconceptions, review schedules
-- (Master Spec §11, §24)

CREATE TABLE skill_states (
    id                UUID PRIMARY KEY,
    learner_id        UUID NOT NULL,
    node_id           UUID NOT NULL,
    mastery           DOUBLE PRECISION NOT NULL CHECK (mastery BETWEEN 0 AND 1),
    attempts          INT  NOT NULL DEFAULT 0,
    correct_count     INT  NOT NULL DEFAULT 0,
    last_practiced_at TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_skill_state UNIQUE (learner_id, node_id)
);

CREATE INDEX ix_skill_states_node ON skill_states (node_id);
CREATE INDEX ix_skill_states_last_practice ON skill_states (last_practiced_at);

CREATE TABLE misconception_states (
    id                    UUID PRIMARY KEY,
    learner_id            UUID NOT NULL,
    misconception_node_id UUID NOT NULL,
    probability           DOUBLE PRECISION NOT NULL CHECK (probability BETWEEN 0 AND 1),
    evidence_count        INT NOT NULL DEFAULT 0,
    last_evidence_at      TIMESTAMPTZ NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_misconception_state UNIQUE (learner_id, misconception_node_id)
);

CREATE TABLE review_schedules (
    id                  UUID PRIMARY KEY,
    learner_id          UUID NOT NULL,
    node_id             UUID NOT NULL,
    due_at              TIMESTAMPTZ NOT NULL,
    reason              VARCHAR(40) NOT NULL DEFAULT 'DECAY_CROSSED_THRESHOLD'
        CONSTRAINT ck_review_reason CHECK (reason IN
            ('DECAY_CROSSED_THRESHOLD', 'TEACHER_ASSIGNED')),
    mastery_at_trigger  DOUBLE PRECISION,
    status              VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_review_status CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED')),
    created_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_review_learner_status ON review_schedules (learner_id, status, due_at);
CREATE INDEX ix_review_status_due ON review_schedules (status, due_at);

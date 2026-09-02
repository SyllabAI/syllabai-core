-- V3: question bank + attempts (Master Spec §10, §12)

CREATE TABLE questions (
    id                    UUID PRIMARY KEY,
    external_ref          VARCHAR(60),
    question_type         VARCHAR(20) NOT NULL DEFAULT 'MCQ_SINGLE'
        CONSTRAINT ck_question_type CHECK (question_type IN ('MCQ_SINGLE', 'SHORT_ANSWER')),
    stem                  TEXT NOT NULL,
    marks                 INT  NOT NULL CHECK (marks > 0),
    difficulty            INT  NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    expected_time_seconds INT  NOT NULL CHECK (expected_time_seconds > 0),
    command_word          VARCHAR(30),
    primary_topic_node_id UUID NOT NULL,
    provenance            VARCHAR(20) NOT NULL DEFAULT 'SEED_DEMO'
        CONSTRAINT ck_question_provenance CHECK (provenance IN
            ('PAST_PAPER', 'TEACHER_AUTHORED', 'SEED_DEMO')),
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    version               INT NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_questions_topic ON questions (primary_topic_node_id) WHERE active;

-- Secondary topic mappings are first-class (Master Spec §10).
CREATE TABLE question_topics (
    id         UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
    node_id    UUID NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_question_topic UNIQUE (question_id, node_id)
);

CREATE TABLE question_options (
    id                   UUID PRIMARY KEY,
    question_id          UUID NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
    label                VARCHAR(4) NOT NULL,
    option_text          TEXT NOT NULL,
    is_correct           BOOLEAN NOT NULL DEFAULT FALSE,
    misconception_node_id UUID,
    ordering             INT NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_question_option_label UNIQUE (question_id, label)
);

CREATE INDEX ix_options_question ON question_options (question_id, ordering);

-- exactly one correct option per MCQ question
CREATE UNIQUE INDEX uq_one_correct_option
    ON question_options (question_id) WHERE is_correct;

CREATE TABLE attempts (
    id               UUID PRIMARY KEY,
    learner_id       UUID NOT NULL,
    question_id      UUID NOT NULL REFERENCES questions (id),
    chosen_option_id UUID,
    correct          BOOLEAN NOT NULL,
    marks_awarded    INT,
    response_time_ms BIGINT NOT NULL CHECK (response_time_ms >= 0),
    confidence_level INT CHECK (confidence_level BETWEEN 1 AND 5),
    self_doubt_flag  BOOLEAN NOT NULL DEFAULT FALSE,
    timed_condition  BOOLEAN NOT NULL DEFAULT FALSE,
    provenance       VARCHAR(40) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_attempts_learner_time ON attempts (learner_id, created_at DESC);
CREATE INDEX ix_attempts_question ON attempts (question_id);

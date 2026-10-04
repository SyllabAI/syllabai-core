-- V62: exam-series calendar + the learner's one bounded target per enrolment
-- (T-C79, ADR-035 D1 ruling 2026-10-04 trace 1a10493e233cb521: option ② — the
-- calendar is curriculum-IMPORTED reference data; the learner contributes one
-- optional picker selection per course, never a date. The rejected ③
-- (learner-entered timetables) stays rejected: there is deliberately no
-- learner-writable date/window column anywhere below.)
--
-- Anti-fabrication for reference data (ADR-035 Ruling section): no row enters
-- without a citable source — source_url + retrieved_at are NOT NULL by
-- schema, and the import service re-validates. Countdowns are derived at
-- READ (ADR-031 doctrine) — nothing here stores days_to_window.

CREATE TABLE exam_series (
    id             UUID PRIMARY KEY,
    board          VARCHAR(50)  NOT NULL,       -- e.g. 'PEARSON_EDEXCEL'
    qualification  VARCHAR(50)  NOT NULL,       -- e.g. 'INTERNATIONAL_GCSE', 'IAL'
    series_code    VARCHAR(60)  NOT NULL,       -- e.g. '2027-may-june'
    label          VARCHAR(120) NOT NULL,       -- e.g. 'May/June 2027'
    window_start   DATE NOT NULL,
    window_end     DATE NOT NULL,
    entry_deadline DATE,                        -- nullable: not always announced
    results_date   DATE,                        -- nullable: not always announced
    published      BOOLEAN NOT NULL DEFAULT TRUE,
    estimated      BOOLEAN NOT NULL DEFAULT FALSE, -- true => clients render '≈', never as fact
    source_url     VARCHAR(500) NOT NULL,       -- the citation (Pearson key-dates document)
    retrieved_at   TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_exam_series UNIQUE (board, qualification, series_code),
    CONSTRAINT ck_exam_series_window CHECK (window_end >= window_start)
);

CREATE INDEX ix_exam_series_lookup ON exam_series (board, qualification, published, window_start);

-- The enrolment binding: one row per (learner, course); the target column is
-- nullable because "Not sure yet" is a first-class honest state. UNIQUE
-- (learner_id, course_slug) IS the "at most one target series per enrolment"
-- constraint. course_slug is the hub registry lane slug (content/courses.json);
-- core stays registry-agnostic (shape-validated, never enumerated here).
CREATE TABLE learner_course_enrolments (
    id               UUID PRIMARY KEY,
    learner_id       UUID NOT NULL,
    course_slug      VARCHAR(100) NOT NULL,
    target_series_id UUID REFERENCES exam_series (id),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_learner_course_enrolment UNIQUE (learner_id, course_slug)
);

CREATE INDEX ix_learner_course_enrolment_series ON learner_course_enrolments (target_series_id);

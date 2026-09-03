-- V8: multi-part assessment + marking model (Master Spec §6.5, §10, §15, §16)
-- Entities: ExamPaper, QuestionVersion, QuestionPart, MarkScheme, MarkPoint,
-- Answer, SmartMarkResult, HumanMark, agreement evaluations; attempts gain
-- marking lifecycle; skill_states gains the timed-vs-untimed fluency gap.
--
-- Science-core note: BKT/BDT/decay parameters are untouched. This migration only
-- adds the content/marking fabric. New evidence semantics (documented, not silent):
--   * an MCQ attempt is graded and emits evidence immediately (existing behaviour);
--   * a structured attempt emits evidence exactly ONCE, at first authoritative
--     marking (human mark, or smart mark once the κ gate has released it) —
--     `attempts.evidence_emitted` guards the double-fire;
--   * later human overrides revise marks for research/κ but never re-run BKT.

-- ── exam papers ─────────────────────────────────────────────────────────────
CREATE TABLE exam_papers (
    id                          UUID PRIMARY KEY,
    subject_id                  UUID NOT NULL REFERENCES subjects (id),
    title                       VARCHAR(200) NOT NULL,
    board                       VARCHAR(40)  NOT NULL,
    qualification               VARCHAR(20)  NOT NULL,
    unit                        VARCHAR(60),
    session_label               VARCHAR(60),
    paper_code                  VARCHAR(30),
    question_paper_document_id  VARCHAR(80),
    mark_scheme_document_id     VARCHAR(80),
    validation_state            VARCHAR(12) NOT NULL DEFAULT 'SUGGESTED'
        CONSTRAINT ck_exam_paper_state CHECK (validation_state IN
            ('SUGGESTED', 'VALIDATED', 'REJECTED')),
    provenance                  VARCHAR(20) NOT NULL DEFAULT 'PAST_PAPER'
        CONSTRAINT ck_exam_paper_provenance CHECK (provenance IN
            ('PAST_PAPER', 'TEACHER_AUTHORED', 'SEED_DEMO')),
    extraction_method           VARCHAR(120),
    created_by                  UUID,
    created_at                  TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_exam_papers_subject ON exam_papers (subject_id);
CREATE UNIQUE INDEX uq_exam_paper_identity
    ON exam_papers (paper_code, session_label) WHERE paper_code IS NOT NULL;

-- ── questions: paper membership + STRUCTURED type ───────────────────────────
ALTER TABLE questions ADD COLUMN exam_paper_id UUID REFERENCES exam_papers (id);

ALTER TABLE questions DROP CONSTRAINT ck_question_type;
ALTER TABLE questions ADD CONSTRAINT ck_question_type CHECK (question_type IN
    ('MCQ_SINGLE', 'SHORT_ANSWER', 'STRUCTURED'));

CREATE INDEX ix_questions_paper ON questions (exam_paper_id) WHERE exam_paper_id IS NOT NULL;

-- ── question versions: immutable content snapshots (Master Spec §10) ────────
CREATE TABLE question_versions (
    id                    UUID PRIMARY KEY,
    question_id           UUID NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
    version               INT NOT NULL CHECK (version > 0),
    stem                  TEXT NOT NULL,
    marks                 INT NOT NULL CHECK (marks > 0),
    difficulty            INT NOT NULL CHECK (difficulty BETWEEN 1 AND 5),
    expected_time_seconds INT NOT NULL CHECK (expected_time_seconds > 0),
    command_word          VARCHAR(30),
    validation_state      VARCHAR(12) NOT NULL DEFAULT 'SUGGESTED'
        CONSTRAINT ck_question_version_state CHECK (validation_state IN
            ('SUGGESTED', 'VALIDATED', 'REJECTED')),
    source_document_id    VARCHAR(80),
    extraction_confidence DOUBLE PRECISION CHECK (extraction_confidence BETWEEN 0 AND 1),
    extraction_method     VARCHAR(120),
    created_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_question_version UNIQUE (question_id, version)
);

CREATE INDEX ix_question_versions_question ON question_versions (question_id, version DESC);

-- every existing bank question gets a v1 snapshot mirroring its flat fields;
-- the seeded MCQs are live-verified demo content, so they are VALIDATED v1
INSERT INTO question_versions
    (id, question_id, version, stem, marks, difficulty, expected_time_seconds,
     command_word, validation_state, extraction_method, created_at)
SELECT gen_random_uuid(), q.id, 1, q.stem, q.marks, q.difficulty,
       q.expected_time_seconds, q.command_word, 'VALIDATED', 'v3-flat-backfill', now()
FROM questions q;

-- ── question parts (lettered/roman sub-questions of a version) ─────────────
CREATE TABLE question_parts (
    id                  UUID PRIMARY KEY,
    question_version_id UUID NOT NULL REFERENCES question_versions (id) ON DELETE CASCADE,
    label               VARCHAR(12) NOT NULL,
    prompt              TEXT NOT NULL,
    command_word        VARCHAR(30),
    marks               INT NOT NULL CHECK (marks >= 0),
    ordering            INT NOT NULL CHECK (ordering >= 0),
    created_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_question_part UNIQUE (question_version_id, label)
);

CREATE INDEX ix_question_parts_version ON question_parts (question_version_id, ordering);

-- ── mark schemes + mark points (Smart Mark §15) ─────────────────────────────
CREATE TABLE mark_schemes (
    id                 UUID PRIMARY KEY,
    question_version_id UUID NOT NULL REFERENCES question_versions (id) ON DELETE CASCADE,
    version_label      VARCHAR(20) NOT NULL DEFAULT '1',
    source_document_id VARCHAR(80),
    validation_state   VARCHAR(12) NOT NULL DEFAULT 'SUGGESTED'
        CONSTRAINT ck_mark_scheme_state CHECK (validation_state IN
            ('SUGGESTED', 'VALIDATED', 'REJECTED')),
    extraction_method  VARCHAR(120),
    created_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_mark_scheme UNIQUE (question_version_id, version_label)
);

CREATE TABLE mark_points (
    id                 UUID PRIMARY KEY,
    mark_scheme_id     UUID NOT NULL REFERENCES mark_schemes (id) ON DELETE CASCADE,
    question_part_id   UUID REFERENCES question_parts (id),
    ref                VARCHAR(20),
    ordering           INT NOT NULL CHECK (ordering >= 0),
    text               TEXT NOT NULL,
    marks              INT NOT NULL CHECK (marks > 0),
    acceptance_criteria JSONB,
    extraction_confidence DOUBLE PRECISION CHECK (extraction_confidence BETWEEN 0 AND 1),
    created_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_mark_points_scheme ON mark_points (mark_scheme_id, ordering);
CREATE INDEX ix_mark_points_part ON mark_points (question_part_id);

-- ── attempts: marking lifecycle + single-evidence guard ─────────────────────
ALTER TABLE attempts ADD COLUMN marking_state VARCHAR(16) NOT NULL DEFAULT 'AUTO_GRADED'
    CONSTRAINT ck_attempt_marking_state CHECK (marking_state IN
        ('AUTO_GRADED', 'PENDING', 'SMART_MARKED', 'HUMAN_MARKED', 'OVERRIDDEN'));
ALTER TABLE attempts ADD COLUMN evidence_emitted BOOLEAN NOT NULL DEFAULT FALSE;

-- pre-V8 attempts are MCQ auto-grades whose evidence already fired at submit
UPDATE attempts SET evidence_emitted = TRUE;

-- ── answers: per-part learner responses ─────────────────────────────────────
CREATE TABLE answers (
    id               UUID PRIMARY KEY,
    attempt_id       UUID NOT NULL REFERENCES attempts (id) ON DELETE CASCADE,
    question_part_id UUID NOT NULL REFERENCES question_parts (id),
    answer_text      TEXT,
    marks_awarded    INT CHECK (marks_awarded >= 0),
    marking_state    VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT ck_answer_marking_state CHECK (marking_state IN
            ('PENDING', 'SMART_MARKED', 'HUMAN_MARKED', 'OVERRIDDEN')),
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_answer_per_part UNIQUE (attempt_id, question_part_id)
);

CREATE INDEX ix_answers_attempt ON answers (attempt_id);
CREATE INDEX ix_answers_part ON answers (question_part_id);
CREATE INDEX ix_answers_state ON answers (marking_state);

-- ── smart mark results: append-only run log (never the final truth) ─────────
CREATE TABLE smart_mark_results (
    id                UUID PRIMARY KEY,
    answer_id         UUID NOT NULL REFERENCES answers (id) ON DELETE CASCADE,
    pipeline_version  VARCHAR(20) NOT NULL,
    model_id          VARCHAR(80),
    marks_awarded     INT NOT NULL CHECK (marks_awarded >= 0),
    confidence        DOUBLE PRECISION CHECK (confidence BETWEEN 0 AND 1),
    validation_passed BOOLEAN NOT NULL,
    breakdown         JSONB,
    failure_reason    VARCHAR(200),
    raw_output        TEXT,
    created_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_smart_mark_results_answer ON smart_mark_results (answer_id, created_at DESC);

-- ── human marks: the authoritative override ─────────────────────────────────
CREATE TABLE human_marks (
    id                  UUID PRIMARY KEY,
    answer_id           UUID NOT NULL REFERENCES answers (id) ON DELETE CASCADE,
    marker_id           UUID NOT NULL,
    marks_awarded       INT NOT NULL CHECK (marks_awarded >= 0),
    per_point_decisions JSONB,
    comments            TEXT,
    created_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_human_marks_answer ON human_marks (answer_id, created_at DESC);

-- ── κ agreement gate records (F-161, κ ≥ 0.60 release gate) ─────────────────
CREATE TABLE smart_mark_agreement_evaluations (
    id                  UUID PRIMARY KEY,
    scope               VARCHAR(12) NOT NULL
        CONSTRAINT ck_agreement_scope CHECK (scope IN ('PAPER', 'ALL')),
    exam_paper_id       UUID REFERENCES exam_papers (id),
    sample_size         INT NOT NULL CHECK (sample_size > 0),
    kappa               DOUBLE PRECISION NOT NULL,
    observed_agreement  DOUBLE PRECISION NOT NULL,
    threshold           DOUBLE PRECISION NOT NULL DEFAULT 0.60,
    passed              BOOLEAN NOT NULL,
    computed_at         TIMESTAMPTZ NOT NULL,
    computed_by         UUID,
    CONSTRAINT ck_agreement_paper CHECK (scope <> 'PAPER' OR exam_paper_id IS NOT NULL)
);

-- ── fluency gap (Paper B §16, F-162) ────────────────────────────────────────
ALTER TABLE skill_states ADD COLUMN procedural_fluency_gap DOUBLE PRECISION
    CHECK (procedural_fluency_gap BETWEEN -1 AND 1);

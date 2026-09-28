-- V49: assignments — the "teacher assigns → learner sees → learner hands in →
-- teacher tracks real completion" contract (ADR-029 tranche 4.10).
--
-- CONTEXT: the hub's teacher Assignments surface always had an honest split:
-- the BUILD half ran on real numbers (the same marks-aware assembly as the
-- Test Builder, against the committed question bank), but the ASSIGN +
-- COLLECT halves were demo-truth — assignments lived in the teacher's
-- browser localStorage ("syllabai.assignments.v1"), the "class" was the
-- SAMPLE cohort, and completion/scores were a deterministic roster sim.
-- A learner in a different browser could never see an assignment, and a
-- teacher could never see a real hand-in. localStorage cannot cross devices;
-- a server contract is the only honest shape for a two-party workflow.
--
-- DESIGN RULES:
--   - CONTENT REFS ARE OPAQUE, HUB-OWNED: course_slug / course_label are the
--     hub's course identity (the hub owns content identity, core owns the
--     workflow state — the same ruling as V47's card_id / V48's note_id).
--     Core has no course registry and deliberately does not pretend to.
--   - TARGETS ARE RESOLVED, NOT CLAIMED (fail-closed, same discipline as
--     V47/V48): every spec ref the teacher targets is resolved against the
--     ingested curriculum knowledge graph at creation and must be a
--     CURRICULUM-STRUCTURE node below the subject root (UNIT/TOPIC/SUBTOPIC).
--     Unknown codes, the subject root and the semantic layer
--     (CONCEPT/MISCONCEPTION) are refused and nothing is written — an
--     assignment can never target work the curriculum does not have.
--   - HAND-IN EVIDENCE IS APPEND-ONLY: every submission action is a row; the
--     latest row per (assignment, learner) is the current hand-in, and a
--     re-hand-in (an improved score, a late catch-up) is new evidence rather
--     than an overwrite. The teacher roster view reads the latest row.
--   - SELF-REPORT, NEVER MASTERY: a hand-in records completion state — no
--     BKT update, no SkillState row, no misconception evidence, no
--     ReviewSchedule. Mastery comes from marked attempts only (the
--     learner-model honesty rule); the real mastery evidence an assignment
--     generates flows through the EXISTING attempt pipeline unchanged.
--     Pinned by AssignmentFlowIT, which asserts SkillStateRepository stays
--     empty after creation + submission traffic.
--   - NO CLASS ENTITY INTRODUCED: the pilot's honest cohort is still the
--     enabled STUDENT set (the TeacherRosterController ruling). Missing =
--     any enabled student without a submission row — computed, never stored,
--     so it cannot drift from the identity truth.
--
-- SCALE NOTE: assignments are teacher-authored (tens, not thousands);
-- submissions are one small row per learner hand-in with an
-- assignment-scoped recent index for the roster view.

CREATE TABLE assignments (
    id            UUID PRIMARY KEY,
    teacher_id    UUID NOT NULL,
    course_slug   VARCHAR(64) NOT NULL,
    course_label  VARCHAR(120) NOT NULL,
    title         VARCHAR(200) NOT NULL,
    spec_refs     JSONB NOT NULL,
    marks_total   INT NOT NULL CHECK (marks_total >= 1),
    question_count INT NOT NULL CHECK (question_count >= 1),
    due_at        TIMESTAMPTZ NOT NULL,
    status        VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_as_created ON assignments (created_at DESC);
CREATE INDEX ix_as_teacher ON assignments (teacher_id);

CREATE TABLE assignment_submissions (
    id                  UUID PRIMARY KEY,
    assignment_id       UUID NOT NULL,
    learner_id          UUID NOT NULL,
    questions_completed INT NOT NULL CHECK (questions_completed >= 0),
    score               INT NULL CHECK (score IS NULL OR score >= 0),
    occurred_at         TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_asub_assignment_recent ON assignment_submissions (assignment_id, occurred_at DESC);
CREATE INDEX ix_asub_learner           ON assignment_submissions (learner_id);

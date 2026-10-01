-- V51: classroom foundation — the explicit Class entity, class membership,
-- announcements, and the classroom student capability layer (TFA-01 + TFA-02,
-- TEACHER_ARCHITECTURE.md §4.2/§8/§9, the Foundation group of §22).
--
-- CONTEXT: V49 deliberately ruled "NO CLASS ENTITY INTRODUCED" — the pilot's
-- honest cohort was the enabled STUDENT set (the TeacherRosterController
-- ruling), and TFA-01/TFA-02 were the registered future work that would
-- replace that shortcut with explicit class membership. This migration is
-- that replacement. The V49 default itself is PRESERVED: an assignment with
-- class_id NULL still targets every enabled student (independent students
-- included); a class-targeted assignment (class_id set) is visible ONLY to
-- that class's members.
--
-- DESIGN RULES:
--   - COURSE REFS ARE OPAQUE, HUB-OWNED (the V47 card_id / V48 noteId /
--     V49 course_slug ruling, reused verbatim): a class carries the hub's
--     course_slug + course_label. Core has no course registry and
--     deliberately does not pretend to. The hub renders the full
--     Board → Qualification → Subject → CurriculumVersion context from its
--     own course registry; core enforces authorization on class OWNERSHIP
--     (teacher_id), which is the subject-scoping boundary that exists
--     server-side today (TEACHER_ARCHITECTURE §17: backend authorization is
--     mandatory — the hub's subject chrome is presentation, never the gate).
--   - MEMBERSHIP IS EXPLICIT AND AUDITED: class_members rows name who
--     enrolled whom. A student may be a member of many classes and remains
--     fully independent outside them — the classroom relationship grants
--     capabilities, it never creates a second learner model (§4.2). No
--     enroll-by-assignment side effects on learner state, ever.
--   - ANNOUNCEMENTS ARE TEACHER-TO-CLASS COMMUNICATION, NOT AN AI CHANNEL
--     (§9): no LLM path touches this module. Categories are the §9
--     vocabulary. Student read state is a separate table so the reads stay
--     append-only and the announcement row never mutates.
--   - CLASSROOM WORKFLOW WRITES NO LEARNER-MODEL STATE: no BKT update, no
--     SkillState, no misconception evidence, no ReviewSchedule from class or
--     announcement traffic — the same honesty rule as V47/V48/V49, pinned by
--     ClassroomFlowIT.
--   - TEACHING COVERAGE IS NOT HERE: taught-vs-not-taught state is TFA-03
--     (its own migration, its own contract). This schema must not imply it.
--
-- SCALE NOTE: classes are teacher-authored (tens); members are hundreds;
-- announcements are tens per class with an in-place index for the student
-- feed; reads are one small row per (announcement, student).

CREATE TABLE classes (
    id           UUID PRIMARY KEY,
    teacher_id   UUID NOT NULL,
    course_slug  VARCHAR(64) NOT NULL,
    course_label VARCHAR(120) NOT NULL,
    name         VARCHAR(120) NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_class_teacher ON classes (teacher_id);
-- one live class per (teacher, course, name): archived rows may be reused
CREATE UNIQUE INDEX ux_class_teacher_course_name
    ON classes (teacher_id, course_slug, lower(name)) WHERE status = 'ACTIVE';

CREATE TABLE class_members (
    id          UUID PRIMARY KEY,
    class_id    UUID NOT NULL,
    student_id  UUID NOT NULL,
    enrolled_by UUID NOT NULL,
    enrolled_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_cmember_class FOREIGN KEY (class_id) REFERENCES classes (id) ON DELETE CASCADE,
    CONSTRAINT ux_cmember_pair UNIQUE (class_id, student_id)
);

CREATE INDEX ix_cmember_student ON class_members (student_id);

CREATE TABLE announcements (
    id         UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    class_id   UUID NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       TEXT NOT NULL,
    category   VARCHAR(20) NOT NULL DEFAULT 'GENERAL'
               CHECK (category IN ('GENERAL', 'HOMEWORK', 'NOTICE', 'EXAM_REMINDER', 'RESOURCE')),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_ann_class FOREIGN KEY (class_id) REFERENCES classes (id) ON DELETE CASCADE
);

CREATE INDEX ix_ann_class_recent ON announcements (class_id, created_at DESC);

CREATE TABLE announcement_reads (
    announcement_id UUID NOT NULL,
    student_id      UUID NOT NULL,
    read_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_ann_read PRIMARY KEY (announcement_id, student_id),
    CONSTRAINT fk_ann_read_ann FOREIGN KEY (announcement_id) REFERENCES announcements (id) ON DELETE CASCADE
);

-- the classroom targeting column on the existing assignments table:
-- NULL (default) = every enabled student, byte-for-byte the V49 behavior;
-- set = visible and hand-in-able by that class's members only.
ALTER TABLE assignments ADD COLUMN class_id UUID NULL REFERENCES classes (id) ON DELETE SET NULL;
CREATE INDEX ix_as_class ON assignments (class_id);

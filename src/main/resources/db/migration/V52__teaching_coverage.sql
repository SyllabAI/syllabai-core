-- V52: teaching coverage — the teacher/class/subject taught-state overlay
-- (TFA-03, TEACHER_ARCHITECTURE.md §13.4 + §19; stacked on the V51 classroom
-- foundation, which this schema composes with).
--
-- THE POINT OF THE WHOLE FEATURE: the Class KG must distinguish not-yet-taught
-- curriculum from taught-but-weak. A class heatmap that renders "grey" for a
-- weak topic the teacher has not reached yet AND for a topic they taught that
-- the class failed is a lie by omission — the teacher cannot tell "my teaching
-- has not arrived there" from "my teaching did not work". This overlay is the
-- separation: coverage state lives HERE, class understanding lives in the
-- learner aggregation; neither derives from the other.
--
-- NOT_TAUGHT IS NOT A MASTERY STATE: nothing in the learner model (BKT,
-- SkillState, misconception evidence, review schedule) may ever read or write
-- this module. Grey graph nodes semantically mean ABSENT TEACHING COVERAGE —
-- the view layer renders "no coverage row" exactly like an explicit
-- NOT_TAUGHT assertion, because both honestly mean "coverage not asserted".
--
-- SHAPE (§19 TeachingCoverage): specification_point_id / graph_node_id,
-- status, recorded_at, teacher_id, provenance/audit metadata.
--   - teaching_coverage: the CURRENT state, one row per (class, spec-point
--     node). Only rows a teacher explicitly asserted exist — absence of a row
--     is the honest "unrecorded" state; the API NEVER fabricates rows for the
--     whole curriculum (182 fabricated NOT_TAUGHTs per class would be
--     invention, not evidence).
--   - teaching_coverage_events: the APPEND-ONLY audit trail — every status
--     change is one event recording previous_status → status, the acting
--     teacher, the note and the timestamp. Events are never updated or
--     deleted; the per-point history endpoint serves them verbatim.
--
-- DESIGN RULES (the V51 discipline continues):
--   - SPEC-POINT SCOPE IS THE V39 INVARIANT: applicability is populated ONLY
--     on seed-owned spec-point rows (ConceptGraphSeedService; 182/182 carry
--     it, backfilled on re-activation), so the write gate refuses any node
--     without it — fail-closed, never a guess. Practicals, structure and
--     concept nodes are not specification points and are refused.
--   - NO CURRICULUM MUTATION: knowledge_nodes is read-only from this module;
--     the spec_point_node_id FK carries the V30 default (NO ACTION) so a
--     curriculum refresh that tried to drop a referenced node fails loudly
--     instead of silently wiping teacher assertions.
--   - CLASS SCOPE: classes FK cascades (a class delete removes its overlay —
--     the class is the scope container, same ruling as class_members and
--     announcements). Classes are archived in practice, never deleted; the
--     cascade is the safety net, not a workflow.
--   - COURSE REFS STAY OPAQUE, HUB-OWNED (V47/V48/V49/V51): no course_slug
--     cross-check against spec-point codes — core cannot parse the hub's
--     slugs without inventing a course registry. The hub only offers its own
--     course's spec points; core enforces what it can honestly know:
--     ownership + node identity.
--   - VOCABULARY IS EXACTLY THE TWO STATES THE ADDENDUM AND §13.4 NAME:
--     TAUGHT | NOT_TAUGHT. No invented REVIEWED — extensions land as additive
--     CHECK migrations with their own contract, never silent enum drift.
--   - TEACHER PROVENANCE ON EVERY ROW: marked_by is the asserting teacher
--     (ownership is verified before any write), marked_at the assertion
--     timestamp; created_at preserves the FIRST assertion on the state row.
--   - TEACHER-ONLY SURFACE: no learner-facing read of this module in TFA-03.
--
-- SCALE NOTE: coverage rows are 182 spec points × tens of classes (thousands);
-- events are one small row per marking change. The composite PK serves the
-- per-class listing and the per-point lookup; the events index serves the
-- per-point history scan.

create table teaching_coverage (
    class_id           uuid not null references classes (id) on delete cascade,
    spec_point_node_id uuid not null references knowledge_nodes (id),
    status             varchar(16) not null
        constraint ck_tcov_status check (status in ('TAUGHT', 'NOT_TAUGHT')),
    marked_by          uuid not null,
    marked_at          timestamptz not null,
    note               varchar(500),
    created_at         timestamptz not null,
    primary key (class_id, spec_point_node_id)
);

create table teaching_coverage_events (
    id                 uuid primary key,
    class_id           uuid not null references classes (id) on delete cascade,
    spec_point_node_id uuid not null references knowledge_nodes (id),
    status             varchar(16) not null
        constraint ck_tcov_event_status check (status in ('TAUGHT', 'NOT_TAUGHT')),
    previous_status    varchar(16)
        constraint ck_tcov_event_prev check (previous_status in ('TAUGHT', 'NOT_TAUGHT')),
    actor_id           uuid not null,
    note               varchar(500),
    created_at         timestamptz not null
);

create index ix_tcov_event_point
    on teaching_coverage_events (class_id, spec_point_node_id, created_at desc);

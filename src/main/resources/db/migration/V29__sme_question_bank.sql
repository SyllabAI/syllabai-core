-- V29: SME question-bank import surface (ADR-026).
--
-- Adds the T-C18-ratified question_spec_points mapping table (fail-closed:
-- spec points must exist as knowledge nodes; PRIMARY/SECONDARY roles), a
-- difficulty_source column recording where a question's 1-5 difficulty came
-- from (SME categorical label mapped easy=2/medium=3/hard=4), and the
-- question_asset binary store mirroring revision_note_asset for stem/
-- solution diagram images shipped inside the corpus package.

-- ── question → specification-point mappings (T-C18 design, ADR-026) ────────
create table question_spec_points (
    id                  uuid primary key,
    question_id         uuid not null references questions (id) on delete cascade,
    spec_point_node_id  uuid not null references knowledge_nodes (id),
    role                varchar(10) not null
        constraint ck_qsp_role check (role in ('PRIMARY', 'SECONDARY')),
    provenance          varchar(40) not null default 'AI_VALIDATED',
    validation_state    varchar(16) not null default 'AI_VALIDATED'
        constraint ck_qsp_state check (validation_state in
            ('AI_VALIDATED', 'HUMAN_VALIDATED')),
    created_at          timestamptz not null,
    constraint uq_question_spec_point unique (question_id, spec_point_node_id)
);

create index ix_qsp_question on question_spec_points (question_id);
create index ix_qsp_node on question_spec_points (spec_point_node_id);

-- ── difficulty provenance (additive, nullable — T-C18 §3) ──────────────────
alter table questions
    add column difficulty_source varchar(30);

-- ── question assets (mirrors revision_note_asset; bytea, package-provided
--    filenames validated containment-safe by the ingest service) ────────────
create table question_asset (
    filename    varchar(512) primary key,
    content_type varchar(128) not null,
    size_bytes  bigint not null,
    bytes       bytea not null,
    ingested_at timestamptz not null,
    constraint question_asset_name_ck check (filename !~ '[/\\]')
);

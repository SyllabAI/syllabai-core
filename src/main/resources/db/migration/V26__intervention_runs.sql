-- E2: bounded, auditable adaptive-intervention execution records.
-- This migration stores execution identity/references only; learner-state mutation
-- remains owned by the learner-model/evidence path.

create table intervention_run (
    run_id uuid primary key,
    learner_id uuid not null references users(id),
    subject_id uuid,
    curriculum_version_id uuid,
    status varchar(16) not null,
    origin varchar(64) not null,
    target_specification_points jsonb not null default '[]'::jsonb,
    question_part_ids jsonb not null default '[]'::jsonb,
    evidence_refs jsonb not null default '[]'::jsonb,
    diagnosis_snapshot_ref text,
    learner_state_snapshot_ref text,
    diagnosis_version varchar(128),
    action_type varchar(32) not null,
    intervention_version varchar(128) not null,
    intervention_hash varchar(128) not null,
    allowed_tool_ids jsonb not null default '[]'::jsonb,
    terminal_outcome varchar(64),
    current_step integer,
    created_at timestamptz not null,
    started_at timestamptz,
    completed_at timestamptz,
    cancelled_at timestamptz,
    constraint intervention_run_status_ck check (status in ('CREATED','ACTIVE','PAUSED','COMPLETED','CANCELLED','FAILED')),
    constraint intervention_run_terminal_ck check (
        (status in ('COMPLETED','CANCELLED','FAILED') and terminal_outcome is not null)
        or status not in ('COMPLETED','CANCELLED','FAILED')
    )
);

create index intervention_run_learner_created_idx
    on intervention_run (learner_id, created_at desc);

create table intervention_run_step (
    step_id uuid primary key,
    run_id uuid not null references intervention_run(run_id),
    sequence_no integer not null,
    status varchar(16) not null,
    observation_type varchar(64) not null,
    input_evidence_ref text,
    output_evidence_ref text,
    blocked_reason text,
    started_at timestamptz not null,
    completed_at timestamptz,
    constraint intervention_run_step_sequence_ck check (sequence_no >= 0),
    constraint intervention_run_step_unique_seq unique (run_id, sequence_no)
);

create index intervention_run_step_run_idx
    on intervention_run_step (run_id, sequence_no);

create table intervention_run_evidence (
    id uuid primary key,
    run_id uuid not null references intervention_run(run_id),
    evidence_ref varchar(512) not null,
    role varchar(32) not null,
    captured_at timestamptz not null
);

create index intervention_run_evidence_run_idx
    on intervention_run_evidence (run_id, captured_at);

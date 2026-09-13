-- V17 (C-4): optimistic-lock version columns for the learner state aggregates.
-- The evidence path (LearnerModelService) does read-modify-write on these rows
-- inside the assessment submit flow; without a version, two concurrent writes
-- (two submissions, or a submission racing the nightly decay batch) silently
-- lost one update — BKT/BDT state under-counted evidence. With @Version, a
-- concurrent write now fails its transaction loudly and maps to HTTP 409.
-- Additive only: existing rows take the default 0; no backfill, no data change.
alter table skill_states add column version bigint not null default 0;
alter table misconception_states add column version bigint not null default 0;

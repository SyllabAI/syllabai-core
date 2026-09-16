-- Revision Notes corpus (pilot): Save My Exams-derived IGCSE Chemistry (4CH1)
-- revision notes ingested by an operator from the syllabai-resources corpus
-- package, served learner-scoped. Content licensing is covered by the corpus
-- repo's LICENSE-DATA.md — authenticated pilot use only, no anonymous surface.

create table revision_note (
    note_id varchar(256) primary key,
    topic_order integer not null,
    topic_title varchar(512) not null,
    subtopic_order integer not null,
    subtopic_title varchar(512) not null,
    note_order integer not null,
    title varchar(512) not null,
    body_md text not null,
    spec_map jsonb not null default '{}'::jsonb,
    spec_point_codes text not null default '',
    source_url text,
    ingested_at timestamptz not null,
    corpus_version varchar(128) not null,
    constraint revision_note_order_unique unique (topic_order, subtopic_order, note_order)
);

create index revision_note_tree_idx
    on revision_note (topic_order, subtopic_order, note_order);

create table revision_note_asset (
    filename varchar(512) primary key,
    content_type varchar(128) not null,
    size_bytes bigint not null,
    bytes bytea not null,
    ingested_at timestamptz not null,
    constraint revision_note_asset_name_ck check (filename !~ '[/\\]')
);

create table revision_note_viewed (
    id uuid primary key,
    user_id uuid not null references users(id),
    note_id varchar(256) not null,
    viewed_at timestamptz not null,
    constraint revision_note_viewed_unique unique (user_id, note_id)
);

-- note_id is a loose reference (no FK): the corpus is replace-all re-ingested
-- and orphan views are swept by the ingest service; a hard FK would make the
-- replace transaction order-dependent for no domain benefit.
create index revision_note_viewed_user_idx
    on revision_note_viewed (user_id, viewed_at desc);

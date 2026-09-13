-- V15: campaign database identity (T-C04 r2 hardening, operator directive 2026-09-13).
--
-- The campaign database carries a durable, queryable identity row so that
-- ANY tooling about to touch it can verify WHERE it is before doing anything:
--
--   * destructive ingestion tooling (repair scripts, orchestrators) must read
--     this row and refuse to run unless it names THIS database and carries the
--     expected campaign label — missing identity → fail closed;
--   * tests never claim campaign identity: a freshly migrated test database
--     leaves this table EMPTY, which is exactly what makes the fail-closed
--     check meaningful;
--   * application startup records an explicit DB identity line
--     (CampaignDbIdentity) so every boot prints/records where it is.
--
-- The row is NOT seeded here: claiming is an explicit act by the campaign
-- startup component (syllabai.campaign.label), never a migration side effect.
CREATE TABLE campaign_db_identity (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    campaign_label VARCHAR(80) NOT NULL,
    db_name VARCHAR(80) NOT NULL,
    host_addr VARCHAR(80),
    core_commit VARCHAR(40),
    note VARCHAR(200),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

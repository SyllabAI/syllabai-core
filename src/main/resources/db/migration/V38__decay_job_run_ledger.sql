-- V38: decay-job run ledger — the run-if-missed catch-up substrate (session-114).
--
-- The nightly forgetting-decay batch historically fired on a single in-process
-- cron tick (03:00 UTC). On the Render free tier the JVM sleeps through that
-- tick whenever no traffic keeps it awake: measured Sept 17-21, five consecutive
-- nights missed (only the Sept 16 run ever fired — CLA-eval traffic happened to
-- keep the instance up). The 02:50 UTC Vercel keep-alive ping (web c4fa766) was
-- supposed to protect the window but its first protected night (Sept 21) also
-- missed — a single-point-of-failure schedule is the wrong shape for a free tier.
--
-- The fix inverts the trigger: a short-interval checker (NightlyDecayJob, every
-- 15 min) runs the batch when the current UTC window's run is missing, so ANY
-- wake completes the nightly promise. The math was already self-healing
-- (decay is anchored at last_practiced_at and recomputed with full elapsed
-- time), so a late run is exact — what was missing was the "did this window
-- already run?" record. DECAY_APPLIED telemetry cannot serve as that record:
-- a night with zero eligible cells legitimately produces zero events, which
-- would be indistinguishable from a missed run. This ledger records every
-- execution — including zero-cell runs — one row per window.
--
-- It also doubles as the pilot's decay audit trail: the t1 protocol must pin
-- ACTUAL decay dates (session-109 finding), and this table is that ledger.

CREATE TABLE decay_job_runs (
    window_start      TIMESTAMPTZ PRIMARY KEY,  -- the 03:00 UTC window anchor this run belongs to
    executed_at       TIMESTAMPTZ NOT NULL,     -- when the run actually fired
    trigger_kind      VARCHAR(20)  NOT NULL,    -- SCHEDULED (on the window tick) | CATCH_UP (late wake)
    decayed           INT          NOT NULL,    -- skill states decayed this run
    reviews_scheduled INT          NOT NULL     -- reviews scheduled this run
);

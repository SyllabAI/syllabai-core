#!/usr/bin/env python3
"""
campaign_db_preflight.py — fail-closed database identity gate for destructive
and semi-destructive campaign tooling (T-C04 r2 hardening, operator directive
2026-09-13, item 2).

Every script that can delete or rewrite campaign data MUST call
`preflight()` before touching a single row. The gate verifies, in order:

  1. TARGET NAME   — the database actually connected to (current_database(),
                     not the connection string) is exactly the expected
                     campaign database. A test database (syllabai_test) or any
                     other name aborts the run.
  2. IDENTITY ROW  — campaign_db_identity (core migration V15) carries the
                     expected campaign label naming THIS database. A database
                     with a missing / UNCLAIMED / mismatched identity row is
                     not the campaign DB: missing identity → fail closed.
  3. LOGGING       — the verified identity line is printed so it lands in the
                     run log (evidence trail; the r1→r2 bridge-record
                     disappearance happened while campaign stdout was lost).

The identity row is claimed by the core app at startup with
`--syllabai.campaign.label=T-C04-CAMPAIGN`; a freshly migrated test database
never claims anything, which is exactly what makes this gate meaningful.
Logging is supplementary evidence only — the checks above are the fix.
"""
import subprocess
import sys

PSQL = "/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/psql"
CONN = ["-h", "127.0.0.1", "-p", "5432", "-U", "syllabai"]

EXPECTED_DB = "syllabai"
EXPECTED_LABEL = "T-C04-CAMPAIGN"
TEST_DB_PATTERN = ("syllabai_test",)  # explicit, not a regex — no accidental matches


def _psql(db, sql):
    r = subprocess.run([PSQL] + CONN + ["-d", db, "-tAc", sql],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"psql failed (db={db}): {r.stderr.strip()}")
    return r.stdout.strip()


def preflight(expected_db: str = EXPECTED_DB, expected_label: str = EXPECTED_LABEL) -> dict:
    """Verify the target is the claimed campaign DB, else abort (exit 1)."""
    # 1. target name — what am I actually connected to?
    actual_db = _psql(expected_db, "SELECT current_database()")
    if actual_db != expected_db:
        print(f"DB IDENTITY GATE FAILED: connected to '{actual_db}', expected "
              f"'{expected_db}' — refusing to operate on a foreign database.",
              file=sys.stderr)
        sys.exit(1)
    if actual_db in TEST_DB_PATTERN or actual_db.endswith("_test"):
        print(f"DB IDENTITY GATE FAILED: '{actual_db}' is a test database — "
              "destructive tooling never targets it.", file=sys.stderr)
        sys.exit(1)

    # 2. identity row — has the campaign startup claimed THIS database?
    try:
        rows = _psql(actual_db,
                     "SELECT campaign_label || '|' || db_name "
                     "FROM campaign_db_identity WHERE id = 1")
    except RuntimeError as e:
        print(f"DB IDENTITY GATE FAILED: identity table unreadable ({e}) — "
              "missing identity → fail closed.", file=sys.stderr)
        sys.exit(1)
    if not rows:
        print(f"DB IDENTITY GATE FAILED: no identity row in '{actual_db}' — "
              "a database the campaign startup never claimed is not the "
              "campaign DB. Missing identity → fail closed.", file=sys.stderr)
        sys.exit(1)
    label, db_name = rows.split("|", 1)
    if label != expected_label or db_name != expected_db:
        print(f"DB IDENTITY GATE FAILED: identity row says label='{label}' "
              f"db='{db_name}', expected label='{expected_label}' "
              f"db='{expected_db}' — refusing to operate.", file=sys.stderr)
        sys.exit(1)

    # 3. evidence line for the run log
    print(f"DB IDENTITY VERIFIED: db={actual_db} label={label} "
          f"(campaign_db_identity row matched, gate passed)", flush=True)
    return {"db": actual_db, "label": label}


if __name__ == "__main__":
    preflight()

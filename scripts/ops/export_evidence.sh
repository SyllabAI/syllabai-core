#!/usr/bin/env bash
# export_evidence.sh — weekly pilot evidence export (DEPLOYMENT.md §4, session-58).
#
# The only irreplaceable pilot data is attempts/answers (learning evidence) and
# the learner state it produced; Neon free tier retains 7 days PITR, so the
# runbook requires a weekly export while the pilot's research data matters.
#
# Usage (operator, from the syllabai-core repo root — credentials are NEVER
# committed; take them from the Render dashboard or the Neon console):
#
#   NEON_DSN='postgres://user:password@ep-xxxx.neon.tech/dbname?sslmode=require' \
#     bash scripts/ops/export_evidence.sh [output-dir]
#
# Output: <output-dir>/syllabai-evidence-YYYY-MM-DD/ with one CSV per table
# plus a manifest (row counts + sha256) so the export is auditable.
# Tables: attempts (immutable evidence), users, user_roles, skill_states,
#         misconception_states, review_schedules.
set -euo pipefail

DSN="${NEON_DSN:?NEON_DSN is required — the Neon connection string (postgres://…?sslmode=require). Take it from the Render dashboard env or the Neon console; never commit it.}"
OUT="${1:-pilot-evidence}"
STAMP="$(date -u +%Y-%m-%d)"
DIR="$OUT/syllabai-evidence-$STAMP"
mkdir -p "$DIR"

TABLES=(attempts users user_roles skill_states misconception_states review_schedules)

for t in "${TABLES[@]}"; do
  echo "→ exporting $t"
  psql "$DSN" -v ON_ERROR_STOP=1 -c "\copy (SELECT * FROM $t ORDER BY 1) TO '$DIR/$t.csv' WITH (FORMAT csv, HEADER true)"
done

{
  echo "SyllabAI pilot evidence export — $STAMP"
  echo "source: production Neon (read-only \\copy SELECT)"
  for t in "${TABLES[@]}"; do
    rows=$(python3 -c "import csv,sys; print(sum(1 for _ in csv.reader(open('$DIR/$t.csv')))-1)")
    echo "$t: $rows rows  sha256=$(sha256sum "$DIR/$t.csv" | cut -d' ' -f1)"
  done
} > "$DIR/MANIFEST.txt"

cat "$DIR/MANIFEST.txt"
echo "✓ export complete: $DIR"

#!/bin/bash
# run_tests_isolated.sh — END-TO-END PROOF that running the complete core test
# suite leaves the campaign database unchanged (T-C04 r2 hardening, operator
# directive 2026-09-13, item 3).
#
# Procedure:
#   1. verify the campaign DB identity fail-closed (campaign_db_preflight.py)
#   2. snapshot the campaign DB: per-table row counts + sha256 of a data-only
#      dump of every content- and campaign-relevant table
#   3. run the FULL core test suite (mvn test — the RequireTestDatabase guard
#      is active for every class; tests target syllabai_test only)
#   4. re-snapshot and compare — ANY difference is a FAILURE
#
# This is the regression gate for the r1→r2 bridge-record disappearance: the
# campaign data must survive an arbitrary number of full suite runs, unchanged.
set -uo pipefail

CORE=/home/z/my-project/repos/syllabai-core
SCRIPTS=/home/z/my-project/scripts
JAVA_HOME=/home/z/toolchain/jdk-25.0.4.1+1
PSQL=/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/psql
PGDUMP=/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/pg_dump
PSQL_ARGS=(-h 127.0.0.1 -p 5432 -U syllabai -d syllabai)
EVIDENCE_DIR=/home/z/my-project/download/isolation-proof
mkdir -p "$EVIDENCE_DIR"

TABLES="exam_papers questions question_versions question_parts mark_schemes mark_points
        documents document_chunks glm_ocr_bridge_records knowledge_nodes knowledge_edges
        question_topics question_options attempts answers smart_mark_results subjects
        curriculum_versions campaign_db_identity"

snapshot() {
    local out_prefix="$1"
    # row counts per table (volatile-free: plain counts)
    for t in $TABLES; do
        local n
        n=$("${PSQL[@]}" "${PSQL_ARGS[@]}" -tAc "SELECT count(*) FROM $t" 2>/dev/null || echo "TABLE-MISSING")
        echo "$t=$n"
    done > "$out_prefix.counts"
    # data checksum: deterministic data-only dump (INSERT format, no owners,
    # sorted by table name for stability). campaign_db_identity is included —
    # its label/db_name must not change; last_seen_at churn would show up here
    # and that is CORRECT: nothing should touch the DB during the suite run.
    # pg_dump 17 emits a RANDOM \restrict token per dump — strip it before
    # hashing or every dump would differ from itself.
    local dump
    dump=$($PGDUMP -a --inserts -O -x \
        $(for t in $TABLES; do echo -n "-t $t "; done) \
        "${PSQL_ARGS[@]}" 2>/dev/null | grep -vE '^\\(un)?restrict ')
    # chain of custody: RETAIN the hashed object, not just its hash — a hash
    # of a discarded dump is self-attestation, not independent evidence.
    printf '%s' "$dump" > "$out_prefix.dump"
    gzip -f "$out_prefix.dump"
    printf '%s' "$dump" | sha256sum | awk '{print $1}' > "$out_prefix.sha256"
}

echo "=== 1. campaign DB identity preflight ==="
python3 "$SCRIPTS/campaign_db_preflight.py" || { echo "PREFLIGHT FAILED"; exit 1; }

echo "=== 2. snapshot campaign DB (before) ==="
snapshot "$EVIDENCE_DIR/before"
echo "before: $(wc -l < "$EVIDENCE_DIR/before.counts") table counts, sha256=$(cat "$EVIDENCE_DIR/before.sha256")"

echo "=== 3. run the complete core test suite ==="
export JAVA_HOME
export PATH="$JAVA_HOME/bin:/home/z/toolchain/apache-maven-3.9.9/bin:$PATH"
cd "$CORE"
mvn -o test > "$EVIDENCE_DIR/suite-run.log" 2>&1
SUITE_RC=$?
grep -E "Tests run: [0-9]+, Failures" "$EVIDENCE_DIR/suite-run.log" | tail -1
if [ $SUITE_RC -ne 0 ]; then
    echo "SUITE FAILED (rc=$SUITE_RC) — suite must be green for the proof to mean anything"
    exit 1
fi

echo "=== 4. snapshot campaign DB (after) and compare ==="
snapshot "$EVIDENCE_DIR/after"

if diff -u "$EVIDENCE_DIR/before.counts" "$EVIDENCE_DIR/after.counts" > "$EVIDENCE_DIR/counts.diff"; then
    echo "row counts: IDENTICAL"
else
    echo "row counts CHANGED:"; cat "$EVIDENCE_DIR/counts.diff"
fi

if [ "$(cat "$EVIDENCE_DIR/before.sha256")" == "$(cat "$EVIDENCE_DIR/after.sha256")" ]; then
    echo "data dump sha256: IDENTICAL ($(cat "$EVIDENCE_DIR/before.sha256"))"
    VERDICT="PASS — full core test suite left the campaign database byte-identical"
else
    echo "DATA CHANGED — dump sha256 differs:"
    echo "  before=$(cat "$EVIDENCE_DIR/before.sha256")"
    echo "  after =$(cat "$EVIDENCE_DIR/after.sha256")"
    VERDICT="FAIL — the test suite mutated the campaign database"
fi

echo "=== verdict ==="
{
    echo "isolation proof — $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
    echo "suite: $(grep -E 'Tests run: [0-9]+, Failures' "$EVIDENCE_DIR/suite-run.log" | tail -1)"
    echo "result: $VERDICT"
} | tee "$EVIDENCE_DIR/verdict.txt"

[ "$VERDICT" == "FAIL — the test suite mutated the campaign database" ] && exit 1
exit 0

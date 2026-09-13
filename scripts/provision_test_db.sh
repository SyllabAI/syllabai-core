#!/bin/bash
# provision_test_db.sh — create/migrate the DISPOSABLE test database (T-C04
# r2 hardening, operator directive 2026-09-13, item 2).
#
# The core test suite must never touch the campaign DB; it targets
# syllabai_test (enforced by the RequireTestDatabase guard). This script
# provisions that database with the real Flyway-migrated schema by booting
# the app against it — the boot prints "CAMPAIGN.DB.IDENTITY ... label=UNCLAIMED"
# and the campaign_db_identity row STAYS UNCLAIMED, which is exactly what the
# destructive-tooling preflight (campaign_db_preflight.py) relies on to refuse
# operating here.
#
# Usage: scripts/provision_test_db.sh
set -euo pipefail

CORE=/home/z/my-project/repos/syllabai-core
JAR="$CORE/target/syllabai-core-0.1.0-SNAPSHOT.jar"
JAVA=/home/z/toolchain/jdk-25.0.4.1+1/bin/java
PSQL=/home/z/toolchain/pgdebs/root/usr/lib/postgresql/17/bin/psql
TEST_DB=syllabai_test

# refuse to run when the jar is missing (build first: mvn -o package -DskipTests)
[ -f "$JAR" ] || { echo "jar missing — run: (cd $CORE && mvn -o package -DskipTests)"; exit 1; }

# 1. create the database if absent
if [ "$($PSQL -h 127.0.0.1 -p 5432 -U syllabai -d syllabai -tAc \
        "SELECT 1 FROM pg_database WHERE datname='$TEST_DB'")" != "1" ]; then
    $PSQL -h 127.0.0.1 -p 5432 -U syllabai -d syllabai -c "CREATE DATABASE $TEST_DB OWNER syllabai;"
    echo "created database $TEST_DB"
fi

# 2. boot the app against it so Flyway migrates and identity stays UNCLAIMED
LOG=$(mktemp)
"$JAVA" -jar "$JAR" \
    --spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/$TEST_DB \
    --syllabai.security.jwt-secret=provision-only-secret-0123456789abcdef \
    --syllabai.glmocr.batch-dir="$(mktemp -d)" > "$LOG" 2>&1 &
APP_PID=$!

# 3. wait for the identity line (Flyway + claim recording both done by then)
for _ in $(seq 1 60); do
    grep -q "CAMPAIGN.DB.IDENTITY" "$LOG" && break
    kill -0 "$APP_PID" 2>/dev/null || break
    sleep 2
done
kill "$APP_PID" 2>/dev/null || true
wait "$APP_PID" 2>/dev/null || true
grep "CAMPAIGN.DB.IDENTITY" "$LOG" || { echo "provisioning FAILED — see $LOG"; exit 1; }

# 4. verify: migrations applied, identity UNCLAIMED
$PSQL -h 127.0.0.1 -p 5432 -U syllabai -d "$TEST_DB" -c \
    "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
$PSQL -h 127.0.0.1 -p 5432 -U syllabai -d "$TEST_DB" -c \
    "SELECT campaign_label, db_name FROM campaign_db_identity;"
echo "$TEST_DB provisioned (identity must read UNCLAIMED|$TEST_DB)"

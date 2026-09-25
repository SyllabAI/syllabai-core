#!/bin/sh
# SyllabAI core start script (session-122).
#
# One job beyond the old inline ENTRYPOINT: guarantee an AppCDS archive is
# mapped on EVERY wake. The Dockerfile's build-time training run bakes
# /app/cds/app.jsa into the image; this script copies it into /tmp (ephemeral,
# container-writable) and lets -XX:+AutoCreateSharedArchive keep using it.
# Any orderly JVM exit re-dumps a full-coverage archive over the /tmp copy
# (runtime top-up); if the copy or the dump ever fails, java simply boots
# without CDS — the exact pre-AppCDS behaviour. No failure path here can
# prevent the JVM from starting.
#
# `exec java` keeps PID 1 / signal semantics intact: Render's SIGTERM reaches
# the JVM directly (graceful shutdown, and the CDS re-dump on clean exit).

set -u

# Observability (session-123): one boot-log line stating whether the baked
# archive made it into this container, so the Render log alone resolves the
# baked-vs-absent fork (the 2026-09-24 measured cold wake, ~177 s, is
# statistically indistinguishable from the pre-AppCDS baseline — we could not
# tell from timing whether the archive is mapping at all). Combined with
# -Xlog:cds=info below (prints "Opened archive ..." on a successful map, or
# the reason it didn't), every wake now self-reports its CDS status.
if [ -s /app/cds/app.jsa ]; then
  echo "AppCDS baked archive present: $(du -h /app/cds/app.jsa 2>/dev/null | cut -f1) (/app/cds/app.jsa)"
else
  echo "AppCDS baked archive ABSENT in this image — wake boots without CDS (non-fatal)"
fi

# Pre-seed the ephemeral archive from the baked one (once per container).
if [ -s /app/cds/app.jsa ] && [ ! -s /tmp/syllabai-appcds.jsa ]; then
  cp /app/cds/app.jsa /tmp/syllabai-appcds.jsa 2>/dev/null || \
    echo "AppCDS: could not pre-seed /tmp archive (non-fatal) — booting without CDS"
fi

exec java \
  -Xlog:cds=info \
  -XX:MaxRAMPercentage=42 \
  -XX:MaxMetaspaceSize=160m \
  -XX:ReservedCodeCacheSize=48m \
  -XX:ActiveProcessorCount=1 \
  -XX:+UseSerialGC \
  -XX:TieredStopAtLevel=1 \
  -Xss512k \
  -XX:+ExitOnOutOfMemoryError \
  -XX:+AutoCreateSharedArchive \
  -XX:SharedArchiveFile=/tmp/syllabai-appcds.jsa \
  -jar app.jar

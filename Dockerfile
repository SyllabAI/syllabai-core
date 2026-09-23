# SyllabAI backend image — Render free tier deployment (T-031).
# Multi-stage: build with Maven+JDK25, run on JRE 25.
# Tags verified against the Docker Hub registry API on 2026-09-10, after the
# first real Render deploy failed at FROM: maven:3.9.9-eclipse-temurin-25 does
# not exist (the maven+temurin-25 family starts at 3.9.11).
#
# JVM flags tuned for the Render free instance (512 MB RAM / 0.1 CPU), after the
# third deploy passed Flyway+Hibernate startup and then stalled silently for
# 10+ minutes at HHH10001005 with no crash line — the GC-thrash signature of
# an unbounded memory footprint against the 512 MB cgroup:
#   MaxRAMPercentage=42      ~215 MB heap (pilot live set is ~80-150 MB)
#   MaxMetaspaceSize=160m    metaspace was UNBOUNDED; Hibernate 7 + Spring AI 2
#                            + Kotlin (openai-java) alone load >100 MB of classes
#   ReservedCodeCacheSize=48m default is 240 MB reserved
#   UseSerialGC              no parallel GC threads competing for the 0.1 CPU;
#                            full GCs stay cheap and short
#   TieredStopAtLevel=1      C1-only JIT: startup is CPU-bound on 0.1 CPU and
#                            C2 compilation of Hibernate/Spring hot paths was
#                            eating the quota; peak throughput loss is
#                            irrelevant for an LLM-latency-bound pilot
#   ActiveProcessorCount=1   match the CPU quota (GC/JIT/ForkJoin sizing)
#   Xss512k                  ~30-40 threads (Tomcat + Hikari + virtual threads)
#   ExitOnOutOfMemoryError   future thrash fails FAST and visibly in the log
#                            instead of stalling silently to a port-scan timeout
#   AutoCreateSharedArchive  AppCDS: build-time TRAINING RUN (below) boots the
#   + SharedArchiveFile      app once during docker build with the DB pointed at
#                            an unreachable localhost socket, so the class-data
#                            archive is BAKED INTO THE IMAGE — every wake maps
#                            it, including the first boot after every deploy.
#                            This replaces the runtime-only dump (session-121's
#                            AutoCreateSharedArchive-to-/tmp), which silently
#                            never engaged: measured 2026-09-23 15:52 UTC, a
#                            cold wake on that build took 184.6 s — at/above
#                            the 105-175 s pre-AppCDS baseline — because a
#                            spin-down that does not end in an orderly JVM
#                            exit never writes /tmp/syllabai-appcds.jsa.
#                            start.sh still keeps the runtime top-up: /tmp is
#                            pre-seeded from the baked archive, and any clean
#                            exit re-dumps a full-coverage archive over it.
#                            Non-fatal by design at every step: the training
#                            run is `timeout`-guarded and ||-guarded (verified
#                            2026-09-23: -XX:ArchiveClassesAtExit writes the
#                            archive even when the JVM exits non-zero), a
#                            missing/corrupt archive just means no CDS, and
#                            SIGKILL only skips the re-dump.
# Sum of maxima ~513 MB, realistic committed RSS ~430-450 MB < 512 MB cgroup
# (the CDS archive adds a shared, mostly file-backed mapping on top — pages
# are evictable under pressure and shared across the process).

FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline -DskipTests
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN useradd -r -u 1001 syllabai
COPY --from=build /app/target/syllabai-core-*.jar app.jar

# AppCDS training run (session-122): boot the REAL app once at BUILD time so
# the class-data archive exists in the image layer — independent of whether
# Render's spin-down ever gives the JVM an orderly exit. The boot runs with
# the full context (no autoconfiguration excluded — no Boot-version-specific
# class names to go stale):
#   - exit=onRefresh            stop right after context refresh, before any
#                               runner/CLI/traffic — a pure class-loading pass
#   - datasource -> 127.0.0.1   unreachable by construction; Hikari skips its
#                               initial connection (initialization-fail-timeout
#                               -1) and CampaignDbIdentity is deliberately
#                               non-fatal on DataAccessException (T-C04 r2)
#   - flyway disabled           no migrations to run without a database
#   - hibernate without JDBC    explicit dialect + use_jdbc_metadata_defaults=
#                               false lets the EntityManagerFactory bootstrap
#                               without a live connection, so JPA repos and the
#                               services that depend on them still get created
#                               (their classes land in the archive)
#   - dummy jwt-secret          the fail-fast blank-secret check needs a value
# If the context fails anywhere anyway, the archive is still written (verified
# 2026-09-23: the dump happens on non-zero exits too) — with every class that
# loaded before the failure. `timeout 300` + `||` make the whole step
# non-fatal for the build.
RUN mkdir -p /app/cds && timeout 300 java \
      -XX:ArchiveClassesAtExit=/app/cds/app.jsa \
      -Dspring.context.exit=onRefresh \
      '-Dspring.datasource.url=jdbc:postgresql://127.0.0.1:5432/appcds_training?connectTimeout=2&socketTimeout=5' \
      -Dspring.datasource.hikari.initialization-fail-timeout=-1 \
      -Dspring.flyway.enabled=false \
      -Dspring.jpa.hibernate.ddl-auto=none \
      -Dspring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults=false \
      -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect \
      -Dsyllabai.security.jwt-secret=appcds-training-secret-0123456789abcdef \
      -jar app.jar \
      || echo "AppCDS training run did not complete cleanly (non-fatal)"; \
    if [ -s /app/cds/app.jsa ]; then \
      echo "AppCDS archive baked: $(du -h /app/cds/app.jsa | cut -f1)"; \
    else \
      echo "AppCDS archive absent — wakes will boot without CDS (non-fatal)"; \
    fi

COPY start.sh /app/start.sh
RUN chmod 755 /app/start.sh
USER syllabai
# Render sets PORT=10000 for web services (default expected port).
EXPOSE 10000
ENTRYPOINT ["/app/start.sh"]

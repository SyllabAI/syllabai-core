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
#   AutoCreateSharedArchive  AppCDS: the FIRST boot after a deploy runs at
#   + SharedArchiveFile      normal speed and dumps a class-data archive to
#                            /tmp at clean JVM exit; every subsequent WAKE of
#                            the same container maps it and skips a large
#                            chunk of class loading/verification — typically
#                            the single biggest saving available on a 0.1-CPU
#                            instance where wakes are the common case and
#                            redeploys are rare. Non-fatal by design: a
#                            missing/corrupt/stale archive is regenerated at
#                            the next exit, and SIGKILL simply means "no
#                            archive this time" (current behaviour). The two
#                            flags can be deleted with no other change.
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
USER syllabai
# Render sets PORT=10000 for web services (default expected port).
EXPOSE 10000
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=42", \
  "-XX:MaxMetaspaceSize=160m", \
  "-XX:ReservedCodeCacheSize=48m", \
  "-XX:ActiveProcessorCount=1", \
  "-XX:+UseSerialGC", \
  "-XX:TieredStopAtLevel=1", \
  "-Xss512k", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+AutoCreateSharedArchive", \
  "-XX:SharedArchiveFile=/tmp/syllabai-appcds.jsa", \
  "-jar", "app.jar"]

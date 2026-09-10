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
# Sum of maxima ~513 MB, realistic committed RSS ~430-450 MB < 512 MB cgroup.

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
  "-jar", "app.jar"]

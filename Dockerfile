# SyllabAI backend image — Render free tier deployment (T-031).
# Multi-stage: build with Maven+JDK25, run on JRE 25.
# NOTE: verify these tags on first Render deploy (T-031); tags current as of 2026-09.

FROM maven:3.9.9-eclipse-temurin-25 AS build
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
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

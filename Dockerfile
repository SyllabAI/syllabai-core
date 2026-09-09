# SyllabAI backend image — Render free tier deployment (T-031).
# Multi-stage: build with Maven+JDK25, run on JRE 25.
# Tags verified against the Docker Hub registry API on 2026-09-10, after the
# first real Render deploy failed at FROM: maven:3.9.9-eclipse-temurin-25 does
# not exist (the maven+temurin-25 family starts at 3.9.11).

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
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

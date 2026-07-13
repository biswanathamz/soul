# soul-orchestrator — real Spring Boot service (the Manager).
# Build context is the REPO ROOT (it needs skillpool/ + hookspool/):
#   docker build -f soul-orchestrator/Dockerfile .
#
# Stage 1: build the fat jar
FROM docker.io/library/gradle:8.10.2-jdk17 AS build
WORKDIR /build
COPY soul-orchestrator/ ./soul-orchestrator/
COPY skillpool/ ./skillpool/
COPY hookspool/ ./hookspool/
WORKDIR /build/soul-orchestrator
RUN gradle bootJar --no-daemon

# Stage 2: run. Needs python3 so the Runner can execute skill/hook entrypoints.
FROM docker.io/library/eclipse-temurin:17-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-yaml curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/soul-orchestrator/build/libs/soul-orchestrator-0.1.0.jar app.jar
COPY skillpool/ /app/skillpool/
COPY hookspool/ /app/hookspool/
# Pools baked in; Ollama + CORS come from compose env.
ENV SOUL_POOLS_SKILLS_PATH=/app/skillpool \
    SOUL_POOLS_HOOKS_PATH=/app/hookspool
EXPOSE 7788
HEALTHCHECK --interval=15s --timeout=3s --retries=10 --start-period=20s \
  CMD curl -fsS http://127.0.0.1:7788/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

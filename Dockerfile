# ---------------------------------------------------------------------------
# DevForge AI — single build definition for every JVM service.
#
# This replaces the 14 near-identical per-service Dockerfiles. Each of those
# ran `mvn -f backend/pom.xml -pl <module> -am package`, so building the full
# platform compiled the reactor 14 times over. Here the Maven work happens once
# in the shared `build` stage and every service image is a thin runtime layer
# on top of it.
#
# Build one service:
#   docker build --target service \
#     --build-arg MODULE_PATH=services/auth-service \
#     --build-arg ARTIFACT_ID=auth-service \
#     --build-arg SERVICE_PORT=9001 \
#     -t devforge-ai/auth-service .
#
# The build context is the repository root, because the reactor parent
# (backend/pom.xml) and common-library live outside each service directory.
# ---------------------------------------------------------------------------

# --- Stage 1: resolve dependencies -----------------------------------------
# Every reactor module must be listed here and in stage 2. common-security and
# common-events were added after this file was written and left out, so the first
# image build ever run (in CI) failed with "Child module ... does not exist".
# Only the POMs are copied here, so this layer is invalidated by dependency
# changes rather than by every source edit.
FROM maven:3.9.9-eclipse-temurin-21 AS deps
WORKDIR /workspace

COPY backend/pom.xml backend/pom.xml
COPY common-library/pom.xml common-library/pom.xml
COPY common-security/pom.xml common-security/pom.xml
COPY common-events/pom.xml common-events/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY services/auth-service/pom.xml services/auth-service/pom.xml
COPY services/project-service/pom.xml services/project-service/pom.xml
COPY services/task-service/pom.xml services/task-service/pom.xml
COPY services/ai-service/pom.xml services/ai-service/pom.xml
COPY services/git-service/pom.xml services/git-service/pom.xml
COPY services/review-service/pom.xml services/review-service/pom.xml
COPY services/documentation-service/pom.xml services/documentation-service/pom.xml
COPY services/chat-service/pom.xml services/chat-service/pom.xml
COPY services/deployment-service/pom.xml services/deployment-service/pom.xml
COPY services/analytics-service/pom.xml services/analytics-service/pom.xml
COPY services/notification-service/pom.xml services/notification-service/pom.xml

# BuildKit cache mount keeps ~/.m2 across builds without baking it into a layer.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f backend/pom.xml dependency:go-offline -DskipTests

# --- Stage 2: compile and package ------------------------------------------
FROM deps AS build
WORKDIR /workspace

COPY common-library/src common-library/src
COPY common-security/src common-security/src
COPY common-events/src common-events/src
COPY api-gateway/src api-gateway/src
COPY services services

# Tests run in CI against real infrastructure, not here: a container build has
# no database, and re-running them per image would be wasteful. CI gates on
# `mvn verify` before any image is built.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f backend/pom.xml clean package -DskipTests

# --- Stage 3: shared runtime base ------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime-base

# wget (busybox) backs the healthcheck; curl is not present in this image.
# Run as a dedicated unprivileged user: the previous images ran as root.
# /app/data exists in the image so a volume mounted there inherits devforge ownership;
# otherwise Docker creates the mount point root-owned and git-service cannot write to it.
# Fixed numeric ids: Kubernetes' runAsNonRoot can only verify a numeric user, and refuses to
# start a container whose user is a name.
RUN addgroup -S -g 10001 devforge \
 && adduser -S -H -u 10001 -G devforge -s /sbin/nologin devforge \
 && mkdir -p /app/data/git-repositories \
 && chown -R devforge:devforge /app

WORKDIR /app
USER 10001:10001

# MaxRAMPercentage makes the JVM respect the container memory limit instead of
# sizing the heap from the host's total RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

# --- Stage 4: per-service runtime ------------------------------------------
FROM runtime-base AS service

ARG MODULE_PATH
ARG ARTIFACT_ID
ARG ARTIFACT_VERSION=0.1.0
ARG SERVICE_PORT

# Promoted to ENV so the healthcheck and entrypoint can read the port, which
# ARG values cannot provide at runtime.
ENV SERVER_PORT=${SERVICE_PORT}

COPY --from=build --chown=devforge:devforge \
     /workspace/${MODULE_PATH}/target/${ARTIFACT_ID}-${ARTIFACT_VERSION}.jar /app/app.jar

EXPOSE ${SERVICE_PORT}

# Actuator's health endpoint is the readiness signal. start-period covers JVM
# and Spring context startup so a slow boot is not reported as a failure.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q --spider "http://127.0.0.1:${SERVER_PORT}/actuator/health" || exit 1

# Exec form: java runs as PID 1 and receives SIGTERM directly, so Spring's
# graceful shutdown runs instead of the container being killed after a timeout.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

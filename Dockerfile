FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM node:22-bookworm-slim AS codex
ARG CODEX_VERSION=0.153.0
RUN npm install --global @openai/codex@${CODEX_VERSION} && npm cache clean --force

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install --no-install-recommends -y ca-certificates git ripgrep python3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 harness && useradd --uid 10001 --gid 10001 --create-home harness \
    && mkdir -p /opt/harness /workspaces /var/lib/harness /home/harness/.codex \
    && chown -R 10001:10001 /workspaces /var/lib/harness /home/harness
COPY --from=codex /usr/local/ /usr/local/
COPY --from=build /build/target/harness-agent-1.0.0-SNAPSHOT.jar /opt/harness/agent.jar
COPY deploy/application-container.yml /opt/harness/application.yml
ENV CODEX_HOME=/home/harness/.codex
USER 10001:10001
WORKDIR /opt/harness
ENTRYPOINT ["java","-jar","/opt/harness/agent.jar","--spring.config.location=file:/opt/harness/application.yml"]

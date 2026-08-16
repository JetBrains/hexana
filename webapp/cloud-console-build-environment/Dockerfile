FROM eclipse-temurin:21-jdk-jammy@sha256:55fb9bf738f5d9b4a6c01b39337e3070d3e27370dd3c478fd1d5d3cd2233c6d8 AS jdk

FROM node:22.14.0-bookworm-slim@sha256:1c18d9ab3af4585870b92e4dbc5cac5a0dc77dd13df1a5905cea89fc720eb05b

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN apt-get update \
    && apt-get install --no-install-recommends --yes bash findutils git \
    && rm -rf /var/lib/apt/lists/*

RUN node --version \
    && java --version \
    && javac --version \
    && bash --version \
    && git --version \
    && xargs --version

USER node
WORKDIR /home/node

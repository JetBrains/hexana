# syntax=docker/dockerfile:1
FROM node:22.14.0-bookworm-slim AS node

FROM gradle:8.10.2-jdk21 AS package
COPY --from=node /usr/local/bin/node /usr/local/bin/node
WORKDIR /workspace
COPY --chown=gradle:gradle . .
ARG HEXANA_SOURCE_REVISION
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 \
    test -n "$HEXANA_SOURCE_REVISION" && \
    printf '%s' "$HEXANA_SOURCE_REVISION" | grep -Eq '^[0-9a-f]{7,64}$' && \
    ./gradlew --no-daemon :webapp:verifyProductionPackage -PhexanaWebappRevision="$HEXANA_SOURCE_REVISION"

FROM nginxinc/nginx-unprivileged:1.27.5-alpine
COPY webapp/nginx.conf /etc/nginx/conf.d/default.conf
COPY webapp/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=package --chown=101:101 /workspace/webapp/build/production-package /usr/share/nginx/html
EXPOSE 8080

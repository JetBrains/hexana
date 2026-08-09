# syntax=docker/dockerfile:1
FROM gradle:8.10.2-jdk21 AS package
WORKDIR /workspace
COPY . .
ARG HEXANA_SOURCE_REVISION
RUN test -n "$HEXANA_SOURCE_REVISION" && \
    printf '%s' "$HEXANA_SOURCE_REVISION" | grep -Eq '^[0-9a-f]{7,64}$' && \
    gradle --no-daemon :webapp:verifyProductionPackage -PhexanaWebappRevision="$HEXANA_SOURCE_REVISION"

FROM nginxinc/nginx-unprivileged:1.27.5-alpine
COPY webapp/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=package --chown=101:101 /workspace/webapp/build/production-package /usr/share/nginx/html
EXPOSE 8080

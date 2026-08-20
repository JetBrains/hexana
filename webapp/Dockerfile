# syntax=docker/dockerfile:1
ARG HEXANA_WEBAPP_PACKAGE_MODE=production

FROM node:22.14.0-bookworm-slim AS production-package-verifier
WORKDIR /verification
COPY webapp/oci-evidence-contract.mjs webapp/gradle.properties ./
COPY webapp/oci-package/production-package/ /package/
ARG HEXANA_WEBAPP_RELEASE_BRANCH
ARG HEXANA_SOURCE_REVISION
ARG HEXANA_SOURCE_URL
RUN test "$HEXANA_SOURCE_URL" = "https://github.com/JetBrains/hexana" && \
    node oci-evidence-contract.mjs verify-package --package-dir /package --version-file gradle.properties \
    --release-branch "$HEXANA_WEBAPP_RELEASE_BRANCH" --revision "$HEXANA_SOURCE_REVISION"

FROM node:22.14.0-bookworm-slim AS staging-package-verifier
WORKDIR /verification
COPY webapp/oci-evidence-contract.mjs ./
COPY webapp/oci-package/staging-package/ /package/
ARG HEXANA_SOURCE_REVISION
ARG HEXANA_SOURCE_URL
RUN test "$HEXANA_SOURCE_URL" = "https://github.com/JetBrains/hexana" && \
    node oci-evidence-contract.mjs verify-staging-package --package-dir /package \
    --revision "$HEXANA_SOURCE_REVISION"

FROM ${HEXANA_WEBAPP_PACKAGE_MODE}-package-verifier AS selected-package-verifier

FROM nginxinc/nginx-unprivileged:1.30.4-alpine3.24@sha256:44e36330f74d4f3a1d4e222acca9e23b401fb87811a7597024502bb759c4dd49
ARG HEXANA_SOURCE_URL
ARG HEXANA_SOURCE_REVISION
LABEL org.opencontainers.image.source="${HEXANA_SOURCE_URL}" \
      org.opencontainers.image.revision="${HEXANA_SOURCE_REVISION}"
USER root
RUN rm -rf /usr/share/nginx/html && \
    mkdir -p /usr/share/nginx/html
COPY webapp/nginx.conf /etc/nginx/conf.d/default.conf
COPY webapp/security-headers.conf /etc/nginx/snippets/security-headers.conf
USER 101
COPY --from=selected-package-verifier --chown=101:101 /package/ /usr/share/nginx/html/
EXPOSE 8080

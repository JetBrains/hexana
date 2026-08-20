import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const read = path => readFile(resolve(root, path), "utf8");
const requireText = (text, fragment, source) => {
    if (!text.includes(fragment)) throw new Error(`${source} is missing ${fragment}`);
};
const forbid = (text, expression, source) => {
    if (expression.test(text)) throw new Error(`${source} contains forbidden ${expression}`);
};
const requireOrdered = (text, fragments, source) => {
    let offset = 0;
    for (const fragment of fragments) {
        const index = text.indexOf(fragment, offset);
        if (index < 0) throw new Error(`${source} is missing ordered fragment ${fragment}`);
        offset = index + fragment.length;
    }
};
const telemetryIngestionOrigin = "https://eu.i.posthog.com";
const runtimeBase = "nginxinc/nginx-unprivileged:1.30.4-alpine3.24@sha256:44e36330f74d4f3a1d4e222acca9e23b401fb87811a7597024502bb759c4dd49";
const productionCsp = `default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' ${telemetryIngestionOrigin}; img-src 'self' data:; worker-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`;
const externalUrls = text => [...text.matchAll(/https?:\/\/[^\s"'<>;]+/g)].map(match => match[0]);
const requireProductionCsp = (policy, source) => {
    if (policy !== productionCsp) throw new Error(`${source} must use the exact production CSP`);
    if (!policy.includes(`connect-src 'self' ${telemetryIngestionOrigin}`)) throw new Error(`${source} must permit the telemetry ingestion origin only via connect-src`);
    if (policy.includes("*") || policy.includes("api.osv.dev")) throw new Error(`${source} weakens the CSP with a wildcard or OSV endpoint`);
    if (JSON.stringify(externalUrls(policy)) !== JSON.stringify([telemetryIngestionOrigin])) throw new Error(`${source} permits an unexpected external URL`);
};
const requireRejected = (policy, source) => {
    try {
        requireProductionCsp(policy, source);
    } catch {
        return;
    }
    throw new Error(`${source} unexpectedly accepted a non-PostHog external endpoint`);
};

const [dockerignore, dockerfile, nginx, securityHeaders, index, buildScript, skaffold, staging, production, stagingGate] = await Promise.all([
    read(".dockerignore"), read("webapp/Dockerfile"), read("webapp/nginx.conf"), read("webapp/security-headers.conf"), read("webapp/src/jsMain/resources/index.html"), read("webapp/build.gradle.kts"), read("webapp/skaffold.yaml"), read("webapp/values_staging.yaml"), read("webapp/values_production.yaml"), read("webapp/stage-cloud-console-staging-package.mjs"),
]);
for (const ignored of [".git", "out", "**/build", "**/node_modules", "**/src/*Test", "vscode-plugin/test", "task.txt"]) requireText(dockerignore, ignored, ".dockerignore");
for (const stagedPackage of ["!webapp/oci-package/production-package/**", "!webapp/oci-package/staging-package/**"]) requireText(dockerignore, stagedPackage, ".dockerignore");
requireText(dockerfile, "FROM node:22.14.0-bookworm-slim AS production-package-verifier", "Dockerfile");
requireText(dockerfile, "COPY webapp/oci-package/production-package/ /package/", "Dockerfile");
requireText(dockerfile, "oci-evidence-contract.mjs verify-package", "Dockerfile");
requireText(dockerfile, "HEXANA_WEBAPP_RELEASE_BRANCH", "Dockerfile");
requireText(dockerfile, "FROM node:22.14.0-bookworm-slim AS staging-package-verifier", "Dockerfile");
requireText(dockerfile, "COPY webapp/oci-package/staging-package/ /package/", "Dockerfile");
requireText(dockerfile, "oci-evidence-contract.mjs verify-staging-package", "Dockerfile");
if (!/^ARG HEXANA_WEBAPP_PACKAGE_MODE=production\s*\n\s*\nFROM /m.test(dockerfile)) throw new Error("Dockerfile must declare the production package mode globally before the verifier stages");
requireText(dockerfile, 'FROM ${HEXANA_WEBAPP_PACKAGE_MODE}-package-verifier AS selected-package-verifier', "Dockerfile");
requireText(dockerfile, "COPY --from=selected-package-verifier --chown=101:101 /package/", "Dockerfile");
forbid(dockerfile, /^COPY\s+--from=\$\{?HEXANA_WEBAPP_PACKAGE_MODE\}?/m, "Dockerfile");
forbid(dockerfile, /^COPY\s+--from=(?:production|staging)-package-verifier\b/m, "Dockerfile");
if ((dockerfile.match(/^COPY\s+--from=selected-package-verifier\b/mg) || []).length !== 1) throw new Error("Dockerfile must copy exactly one selected verifier package into the runtime image");
requireText(dockerfile, "org.opencontainers.image.source", "Dockerfile");
requireText(dockerfile, "org.opencontainers.image.revision", "Dockerfile");
requireText(dockerfile, "COPY webapp/security-headers.conf /etc/nginx/snippets/security-headers.conf", "Dockerfile");
const runtimeFrom = dockerfile.match(/^FROM\s+nginxinc\/nginx-unprivileged:\S+$/gm)?.at(-1);
if (runtimeFrom !== `FROM ${runtimeBase}`) throw new Error(`Dockerfile runtime stage must use ${runtimeBase}`);
const runtimeStage = dockerfile.slice(dockerfile.lastIndexOf(`FROM ${runtimeBase}`));
requireOrdered(runtimeStage, ["USER root", "RUN rm -rf /usr/share/nginx/html", "mkdir -p /usr/share/nginx/html", "USER 101", "COPY --from=selected-package-verifier --chown=101:101 /package/"], "Dockerfile runtime stage");
if (runtimeStage.match(/^USER\s+\S+/gm)?.at(-1) !== "USER 101") throw new Error("Dockerfile runtime stage must restore USER 101 as its final user");
forbid(dockerfile, /\b(latest|stable|mainline)\b/i, "Dockerfile");
for (const header of ["Content-Security-Policy", "X-Content-Type-Options", "Referrer-Policy", "Permissions-Policy", "X-Frame-Options", "Cross-Origin-Opener-Policy", "Cross-Origin-Embedder-Policy", "Cross-Origin-Resource-Policy"]) requireText(securityHeaders, header, "security-headers.conf");
requireText(securityHeaders, "'wasm-unsafe-eval'", "security-headers.conf");
forbid(securityHeaders, /['"]unsafe-eval['"]/, "security-headers.conf");
const indexCsp = index.match(/http-equiv="Content-Security-Policy"\s+content="([^"]+)"/s)?.[1];
const nginxCsp = securityHeaders.match(/add_header Content-Security-Policy "([^"]+)" always;/)?.[1];
const packageCsp = buildScript.match(/private val productionCsp = "([^"]+)"/)?.[1];
for (const [source, csp] of [["index.html", indexCsp], ["security-headers.conf", nginxCsp], ["build.gradle.kts hosting policy", packageCsp]]) {
    if (!csp) throw new Error(`${source} is missing a production CSP`);
    requireProductionCsp(csp, source);
}
requireRejected(productionCsp.replace(telemetryIngestionOrigin, "https://evil.example"), "negative CSP fixture");
if ((nginx.match(/include \/etc\/nginx\/snippets\/security-headers\.conf;/g) || []).length !== 5) throw new Error("nginx.conf must apply security headers at the server and every cache-policy location");
for (const location of ["location = /healthz", "location = /index.html", "location /assets/", "location / {"]) {
    const config = nginx.split("\n").find(line => line.includes(location));
    if (!config?.includes("limit_except GET HEAD")) throw new Error(`${location} must allow only GET and HEAD`);
}
forbid(`${nginx}\n${securityHeaders}`, /Strict-Transport-Security|proxy_pass|fastcgi_pass|dav_methods|autoindex\s+on/i, "Nginx configuration");
requireText(skaffold, 'HEXANA_SOURCE_REVISION: "{{.HEXANA_SOURCE_REVISION}}"', "skaffold.yaml");
requireText(skaffold, 'HEXANA_WEBAPP_RELEASE_BRANCH: "{{.HEXANA_WEBAPP_RELEASE_BRANCH}}"', "skaffold.yaml");
requireText(skaffold, 'HEXANA_SOURCE_URL: "https://github.com/JetBrains/hexana"', "skaffold.yaml");
forbid(skaffold, /HEXANA_SOURCE_REVISION:\s*["']?\{\{\.IMAGE_TAG\}\}/, "skaffold.yaml");
requireText(skaffold, "registry.jetbrains.team/p/hx/containers/hexana-webapp", "skaffold.yaml");
requireText(skaffold, "context: ..", "skaffold.yaml");
requireText(skaffold, "dockerfile: webapp/Dockerfile", "skaffold.yaml");
requireText(skaffold, "oci://registry.jetbrains.team/p/cb/helm-charts/simple-app", "skaffold.yaml");
for (const profile of ["name: staging", "name: production"]) requireText(skaffold, profile, "skaffold.yaml");
requireText(skaffold, "stage-cloud-console-staging-package.mjs", "skaffold.yaml");
requireText(skaffold, "path: /build/artifacts/0/hooks", "skaffold.yaml");
requireText(skaffold, "path: /build/artifacts/0/docker/buildArgs/HEXANA_WEBAPP_RELEASE_BRANCH", "skaffold.yaml");
requireText(skaffold, "HEXANA_WEBAPP_PACKAGE_MODE", "skaffold.yaml");
requireText(skaffold, "value: staging", "skaffold.yaml");
requireText(buildScript, "stageCloudConsoleStagingPackage", "build.gradle.kts");
requireText(buildScript, "dependsOn(\"verifyCloudConsoleDeploymentContract\", packageProductionWebapp)", "build.gradle.kts");
for (const required of ["HEXANA_SOURCE_REVISION must be a lowercase 40-character Git SHA", "HEXANA_SOURCE_REVISION must match the checked-out Git revision", "clean working tree", "verify-staging-package"]) requireText(stagingGate, required, "stage-cloud-console-staging-package.mjs");
forbid(skaffold, /^\s*(namespace|hostname|secret|password|token):/im, "skaffold.yaml");
for (const [name, values] of [["values_staging.yaml", staging], ["values_production.yaml", production]]) {
    for (const required of ["replicaCount: 1", "enabled: false", "runAsUser: 101", "containerPort: 8080", "path: /healthz", "type: RollingUpdate", "requests:", "limits:"]) requireText(values, required, name);
    forbid(values, /pvc:|secret|password|token|namespace|host(name)?|ingress/i, name);
}

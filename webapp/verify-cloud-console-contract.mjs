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

const [dockerignore, dockerfile, nginx, securityHeaders, skaffold, staging, production] = await Promise.all([
    read(".dockerignore"), read("webapp/Dockerfile"), read("webapp/nginx.conf"), read("webapp/security-headers.conf"), read("webapp/skaffold.yaml"), read("webapp/values_staging.yaml"), read("webapp/values_production.yaml"),
]);
for (const ignored of [".git", "out", "**/build", "**/node_modules", "**/src/*Test", "vscode-plugin/test", "task.txt"]) requireText(dockerignore, ignored, ".dockerignore");
requireText(dockerfile, "FROM gradle:8.10.2-jdk21 AS package", "Dockerfile");
requireText(dockerfile, "FROM node:22.14.0-bookworm-slim AS node", "Dockerfile");
requireText(dockerfile, "COPY --from=node /usr/local/bin/node /usr/local/bin/node", "Dockerfile");
requireText(dockerfile, ":webapp:verifyProductionPackage", "Dockerfile");
requireText(dockerfile, "./gradlew --no-daemon", "Dockerfile");
requireText(dockerfile, "-PhexanaWebappRevision", "Dockerfile");
requireText(dockerfile, "COPY webapp/security-headers.conf /etc/nginx/snippets/security-headers.conf", "Dockerfile");
requireText(dockerfile, "COPY --from=package --chown=101:101 /workspace/webapp/build/production-package", "Dockerfile");
requireText(dockerfile, "nginxinc/nginx-unprivileged:1.27.5-alpine", "Dockerfile");
forbid(dockerfile, /\b(latest|stable|mainline)\b/i, "Dockerfile");
for (const header of ["Content-Security-Policy", "X-Content-Type-Options", "Referrer-Policy", "Permissions-Policy", "X-Frame-Options", "Cross-Origin-Opener-Policy", "Cross-Origin-Embedder-Policy", "Cross-Origin-Resource-Policy"]) requireText(securityHeaders, header, "security-headers.conf");
requireText(securityHeaders, "'wasm-unsafe-eval'", "security-headers.conf");
forbid(securityHeaders, /['"]unsafe-eval['"]/, "security-headers.conf");
if ((nginx.match(/include \/etc\/nginx\/snippets\/security-headers\.conf;/g) || []).length !== 5) throw new Error("nginx.conf must apply security headers at the server and every cache-policy location");
for (const location of ["location = /healthz", "location = /index.html", "location /assets/", "location / {"]) {
    const config = nginx.split("\n").find(line => line.includes(location));
    if (!config?.includes("limit_except GET HEAD")) throw new Error(`${location} must allow only GET and HEAD`);
}
forbid(`${nginx}\n${securityHeaders}`, /Strict-Transport-Security|proxy_pass|fastcgi_pass|dav_methods|autoindex\s+on/i, "Nginx configuration");
requireText(skaffold, 'HEXANA_SOURCE_REVISION: "{{.HEXANA_SOURCE_REVISION}}"', "skaffold.yaml");
forbid(skaffold, /HEXANA_SOURCE_REVISION:\s*["']?\{\{\.IMAGE_TAG\}\}/, "skaffold.yaml");
requireText(skaffold, "registry.jetbrains.team/p/hx/containers/hexana-webapp", "skaffold.yaml");
requireText(skaffold, "context: ..", "skaffold.yaml");
requireText(skaffold, "dockerfile: webapp/Dockerfile", "skaffold.yaml");
requireText(skaffold, "oci://registry.jetbrains.team/p/cb/helm-charts/simple-app", "skaffold.yaml");
for (const profile of ["name: staging", "name: production"]) requireText(skaffold, profile, "skaffold.yaml");
forbid(skaffold, /^\s*(namespace|hostname|secret|password|token):/im, "skaffold.yaml");
for (const [name, values] of [["values_staging.yaml", staging], ["values_production.yaml", production]]) {
    for (const required of ["replicaCount: 1", "enabled: false", "runAsUser: 101", "containerPort: 8080", "path: /healthz", "type: RollingUpdate", "requests:", "limits:"]) requireText(values, required, name);
    forbid(values, /pvc:|secret|password|token|namespace|host(name)?|ingress/i, name);
}

import { randomUUID } from "node:crypto";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repository = "registry.jetbrains.team/p/hx/containers/hexana-webapp";
const branchPattern = /^release\/webapp\/(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const revisionPattern = /^[0-9a-f]{40}$/;
const digestPattern = /^sha256:[0-9a-f]{64}$/;
const versionPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const evidenceFiles = ["build-identity.json", "checksums.sha256", "package-manifest.json", "provenance.json", "release-info.json", "resolved-dependencies.json", "sbom.cdx.json"];
const approvedVcsUrl = "https://example.invalid/hexana/Hexana.git";
const approvedParameters = Object.freeze({
    "env.APPLICATION_NAME": "hexana-webapp",
    "env.K8S_NAMESPACE": "hexana-namespace",
    "env.IMAGE_PULL_SECRET": "registry-pull-secret",
    "env.JBCC_GCP_CLUSTER": "example-cluster",
    "env.JBCC_GCP_PROJECT": "example-gcp-project",
    "env.JBCC_GCP_REGION": "europe-west1",
});
const normalizeGeneratedScript = value => {
    const normalized = [];
    let rawHeredocLines = 0;
    for (const rawLine of value.replaceAll("\r\n", "\n").split("\n")) {
        if (rawHeredocLines > 0) {
            normalized.push(rawLine);
            rawHeredocLines--;
            continue;
        }
        const line = rawLine.trim();
        if (!line) continue;
        normalized.push(line.replace(/^skaffold(?:_\d+(?:\.\d+)*)? (config set --global collect-metrics false|run \$\{SKAFFOLD_OPTIONS\} 2>&1)$/, "skaffold $1"));
        if (line === `cat > "$KUBECONFIG" <<'EOF'`) rawHeredocLines = 2;
    }
    return normalized.join("\n");
};
const approvedGeneratedScript = normalizeGeneratedScript(`#!/bin/bash
set -euo pipefail
export PATH=$PATH:/usr/local/custom-bin

echo "Configure helm"
helm version
echo %registry.token% | helm registry login registry.jetbrains.team -u %registry.login% --password-stdin

echo "Setup kubeconfig"
export JBCC_TEMP_KUBECONFIG=$(mktemp); chmod 600 "$JBCC_TEMP_KUBECONFIG"; trap 'rm -f "$JBCC_TEMP_KUBECONFIG"' EXIT;
export KUBECONFIG="$JBCC_TEMP_KUBECONFIG"
cat > "$KUBECONFIG" <<'EOF'
%kubeconfig%
EOF
jbcc-oidc-cluster-auth

echo "Run skaffold"

export SKAFFOLD_OPTIONS="--assume-yes=true -n \${K8S_NAMESPACE} --status-check -p deploy_only_production --cache-artifacts=false"
export API_VERSION=$(cat skaffold.yaml | grep apiVersion | sed "s/apiVersion: skaffold\\/v\\([0-9]\\).*/\\1/")

if [ "$API_VERSION" -gt "2" ]; then
  skaffold_2.10.1 config set --global collect-metrics false
  skaffold_2.10.1 run \${SKAFFOLD_OPTIONS} 2>&1
else
  skaffold config set --global collect-metrics false
  skaffold run \${SKAFFOLD_OPTIONS} 2>&1
fi`);
export const deploymentTiming = Object.freeze({
    buildTimeoutMs: 3_600_000,
    readinessTimeoutMs: 300_000,
    pollIntervalMs: 5_000,
});
export const productionEndpoints = Object.freeze([
    "https://hexana-webapp-private.example.invalid/",
    "https://hexana-webapp.example.invalid/",
]);
export const readinessBodyLimits = Object.freeze({ healthBytes: 0, rootBytes: 1_048_576 });
export const allowedTarget = Object.freeze({
    serverUrl: "https://ci.example.invalid",
    buildTypeId: "HexanaCi_Deploy_HexanaWebapp",
    vcsRootId: "HexanaCi_Deploy_HexanaWebappSshGitGitJetbrainsTeamHxHexanaGit",
});
const fail = message => { throw new Error(`Webapp deployment refused: ${message}`); };
const object = (value, label) => value && typeof value === "object" && !Array.isArray(value) ? value : fail(`${label} must be an object`);
const exactKeys = (value, keys, label) => {
    object(value, label);
    if (Object.keys(value).sort().join("\0") !== [...keys].sort().join("\0")) fail(`${label} has an unsupported shape`);
};
const required = (env, name) => typeof env[name] === "string" && env[name].trim() ? env[name].trim() : fail(`${name} is required`);
const validatePinnedTarget = config => {
    if (config.serverUrl !== allowedTarget.serverUrl || config.buildTypeId !== allowedTarget.buildTypeId || config.vcsRootId !== allowedTarget.vcsRootId) fail("Cloud Console target differs from the source-pinned configuration");
};

export function parseJsonStrict(source, label) {
    let value;
    try { value = JSON.parse(source); } catch { fail(`${label} is not JSON`); }
    const stack = [];
    for (let index = 0; index < source.length; index++) {
        const character = source[index];
        if (character === "{") stack.push(new Set());
        else if (character === "[") stack.push(null);
        else if (character === "}" || character === "]") stack.pop();
        else if (character === '"') {
            let end = index + 1;
            for (; end < source.length; end++) {
                if (source[end] === "\\") end++;
                else if (source[end] === '"') break;
            }
            let next = end + 1;
            while (/\s/.test(source[next] ?? "")) next++;
            const keys = stack.at(-1);
            if (source[next] === ":" && keys) {
                const key = JSON.parse(source.slice(index, end + 1));
                if (keys.has(key)) fail(`${label} contains duplicate key ${key}`);
                keys.add(key);
            }
            index = end;
        }
    }
    return value;
}

export function validateEvidence(receipt, scan, selectedBranch, selectedRevision) {
    const series = branchPattern.exec(selectedBranch ?? "") ?? fail("selected branch must be release/webapp/<numeric-major>.<numeric-minor>");
    if (!revisionPattern.test(selectedRevision ?? "")) fail("selected revision must be lowercase full 40-character hex");
    exactKeys(receipt, ["schemaVersion", "kind", "releaseBranch", "sourceRevision", "image", "package"], "Build receipt");
    if (receipt.schemaVersion !== 1 || receipt.kind !== "hexana-webapp-oci-evidence" || receipt.releaseBranch !== selectedBranch || receipt.sourceRevision !== selectedRevision) fail("Build receipt schema or source identity differs");
    exactKeys(receipt.image, ["reference", "manifestDigest", "configDigest", "manifestMediaType", "configMediaType", "platform"], "Build image");
    exactKeys(receipt.image.platform, ["os", "architecture"], "Build platform");
    if (!digestPattern.test(receipt.image.manifestDigest) || !digestPattern.test(receipt.image.configDigest) || receipt.image.reference !== `${repository}@${receipt.image.manifestDigest}`) fail("Build image is not the canonical immutable digest reference");
    const mediaTypes = [["application/vnd.oci.image.manifest.v1+json", "application/vnd.oci.image.config.v1+json"], ["application/vnd.docker.distribution.manifest.v2+json", "application/vnd.docker.container.image.v1+json"]];
    if (!mediaTypes.some(([manifest, config]) => receipt.image.manifestMediaType === manifest && receipt.image.configMediaType === config)) fail("Build image media types do not identify one supported manifest");
    if (receipt.image.platform.os !== "linux" || receipt.image.platform.architecture !== "amd64") fail("Build image is not linux/amd64");
    exactKeys(receipt.package, ["identity", "aggregateSha256", "evidence"], "Build package");
    exactKeys(receipt.package.identity, ["schemaVersion", "product", "version", "versionSource", "revision"], "Build package identity");
    const identity = receipt.package.identity;
    const version = versionPattern.exec(identity.version ?? "");
    if (identity.schemaVersion !== 1 || identity.product !== "hexana-webapp" || identity.versionSource !== "webapp/gradle.properties" || identity.revision !== selectedRevision || !version || version[1] !== series[1] || version[2] !== series[2]) fail("Build package identity does not match the selected release series");
    exactKeys(receipt.package.evidence, evidenceFiles, "Build package evidence");
    if (!/^[0-9a-f]{64}$/.test(receipt.package.aggregateSha256 ?? "") || !Object.values(receipt.package.evidence).every(value => /^[0-9a-f]{64}$/.test(value))) fail("Build package hashes are malformed");

    exactKeys(scan, ["schemaVersion", "kind", "decision", "evaluatedAt", "release", "image", "sbomSha256", "scanner", "database", "reports", "counts", "findings"], "Scan summary");
    if (scan.schemaVersion !== 1 || scan.kind !== "hexana-webapp-vulnerability-evidence" || scan.decision !== "pass") fail("Scan summary is not a passing supported gate");
    exactKeys(scan.release, ["branch", "revision", "version"], "Scan release");
    exactKeys(scan.image, ["reference", "manifestDigest", "configDigest", "archiveSha256"], "Scan image");
    exactKeys(scan.counts, ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"], "Scan counts");
    for (const [value, label] of [[scan.scanner, "Scan scanner"], [scan.database, "Scan database"], [scan.reports, "Scan reports"]]) object(value, label);
    if (typeof scan.evaluatedAt !== "string" || !scan.evaluatedAt.endsWith("Z") || Number.isNaN(Date.parse(scan.evaluatedAt)) || !/^[0-9a-f]{64}$/.test(scan.sbomSha256 ?? "") || !/^[0-9a-f]{64}$/.test(scan.image.archiveSha256 ?? "")) fail("Scan summary metadata is malformed");
    if (scan.release.branch !== selectedBranch || scan.release.revision !== selectedRevision || scan.release.version !== identity.version || scan.image.reference !== receipt.image.reference || scan.image.manifestDigest !== receipt.image.manifestDigest || scan.image.configDigest !== receipt.image.configDigest) fail("Scan summary differs from Build evidence");
    const severities = ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"];
    if (!Array.isArray(scan.findings) || !Object.values(scan.counts).every(Number.isInteger) || Object.values(scan.counts).some(count => count < 0) || scan.findings.some(finding => !finding || typeof finding !== "object" || !severities.includes(finding.severity))) fail("Scan findings are malformed");
    if (severities.some(severity => scan.counts[severity] !== scan.findings.filter(finding => finding.severity === severity).length) || scan.counts.HIGH !== 0 || scan.counts.CRITICAL !== 0) fail("Scan summary must contain zero HIGH and CRITICAL findings");
    return { branch: selectedBranch, revision: selectedRevision, version: identity.version, image: receipt.image.reference, digest: receipt.image.manifestDigest };
}

const boundedInteger = (value, fallback, minimum, maximum, label) => {
    const parsed = value === undefined ? fallback : Number(value);
    return Number.isInteger(parsed) && parsed >= minimum && parsed <= maximum ? parsed : fail(`${label} is outside its allowed range`);
};
const optionalBuildId = (env, name) => {
    if (env[name] === undefined) return undefined;
    const value = /^\d+$/.test(env[name]) ? Number(env[name]) : NaN;
    return Number.isSafeInteger(value) && value > 0 ? value : fail(`${name} must be a positive numeric build ID`);
};

export function configFromEnv(env) {
    let server;
    try { server = new URL(required(env, "TEAMCITY_SERVER_URL")); } catch { fail("TEAMCITY_SERVER_URL is invalid"); }
    if (server.origin !== allowedTarget.serverUrl || server.pathname !== "/" || server.username || server.password || server.search || server.hash) fail("TEAMCITY_SERVER_URL differs from the pinned TeamCity origin");
    const identifier = /^[A-Za-z0-9_.-]+$/;
    const buildTypeId = required(env, "HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID");
    const vcsRootId = required(env, "HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID");
    if (!identifier.test(buildTypeId) || !identifier.test(vcsRootId)) fail("Cloud Console TeamCity identifiers are invalid");
    if (buildTypeId !== allowedTarget.buildTypeId || vcsRootId !== allowedTarget.vcsRootId) fail("Cloud Console target differs from the pinned generated configuration");
    const buildTimeoutMs = boundedInteger(env.HEXANA_DEPLOY_TIMEOUT_MS, deploymentTiming.buildTimeoutMs, 1_000, deploymentTiming.buildTimeoutMs, "HEXANA_DEPLOY_TIMEOUT_MS");
    const readinessTimeoutMs = boundedInteger(env.HEXANA_READINESS_TIMEOUT_MS, deploymentTiming.readinessTimeoutMs, 1_000, deploymentTiming.readinessTimeoutMs, "HEXANA_READINESS_TIMEOUT_MS");
    const pollIntervalMs = boundedInteger(env.HEXANA_DEPLOY_POLL_INTERVAL_MS, deploymentTiming.pollIntervalMs, 100, 60_000, "HEXANA_DEPLOY_POLL_INTERVAL_MS");
    if (pollIntervalMs >= buildTimeoutMs || pollIntervalMs >= readinessTimeoutMs) fail("poll interval must be shorter than both timeouts");
    const token = required(env, "HEXANA_CLOUD_CONSOLE_TOKEN");
    if (/[\x00-\x20]/.test(token)) fail("HEXANA_CLOUD_CONSOLE_TOKEN is malformed");
    return { serverUrl: allowedTarget.serverUrl, token, buildTypeId, vcsRootId, branch: required(env, "HEXANA_RELEASE_BRANCH"), revision: required(env, "HEXANA_RELEASE_REVISION"), buildTimeoutMs, readinessTimeoutMs, pollIntervalMs, sourceBuilds: { release: optionalBuildId(env, "TEAMCITY_BUILD_ID"), build: optionalBuildId(env, "HEXANA_WEBAPP_BUILD_ID"), scan: optionalBuildId(env, "HEXANA_WEBAPP_SCAN_BUILD_ID") } };
}

export function createVcsRootInstancesRequest(config) {
    return { url: `${config.serverUrl}/app/rest/vcs-root-instances?locator=vcsRoot:(id:${config.vcsRootId})&fields=vcs-root-instance(id,vcs-root-id)`, init: { headers: { Accept: "application/json", Authorization: `Bearer ${config.token}` } } };
}

export function createSafetyPreflightRequests(config) {
    const headers = { Accept: "application/json", Authorization: `Bearer ${config.token}` };
    return [
        { url: `${config.serverUrl}/app/rest/buildTypes/id:${config.buildTypeId}?fields=id,vcs-root-entries(vcs-root-entry(vcs-root(id))),steps(step(type,properties(property(name,value)))),triggers(trigger(id,type,disabled)),parameters(property(name,value))`, init: { headers } },
        { url: `${config.serverUrl}/app/rest/vcs-roots/id:${config.vcsRootId}?fields=id,properties(property(name,value))`, init: { headers } },
    ];
}

const requireArray = (value, label) => Array.isArray(value) ? value : fail(`${label} must be an array`);
const properties = (value, label) => {
    exactKeys(value, ["property"], label);
    return requireArray(value.property, `${label} property`).map((property, index) => { exactKeys(property, ["name", "value"], `${label} property ${index}`); return property; });
};

export function validateSafetyPreflight(buildType, vcsRoot, config) {
    exactKeys(buildType, ["id", "vcs-root-entries", "steps", "triggers", "parameters"], "Cloud Console build type");
    if (buildType.id !== config.buildTypeId) fail("Cloud Console build type ID differs");
    exactKeys(buildType["vcs-root-entries"], ["vcs-root-entry"], "Cloud Console VCS root entries");
    const entries = requireArray(buildType["vcs-root-entries"]["vcs-root-entry"], "Cloud Console VCS root entries");
    if (entries.length !== 1) fail("Cloud Console build type must attach exactly one VCS root");
    exactKeys(entries[0], ["vcs-root"], "Cloud Console VCS root entry");
    exactKeys(entries[0]["vcs-root"], ["id"], "Cloud Console attached VCS root");
    if (entries[0]["vcs-root"].id !== config.vcsRootId) fail("Cloud Console build type attaches the wrong VCS root");

    exactKeys(buildType.parameters, ["property"], "Cloud Console build parameters");
    const buildParameters = requireArray(buildType.parameters.property, "Cloud Console build parameters property");
    for (const [name, value] of Object.entries(approvedParameters)) {
        const matches = buildParameters.filter(parameter => parameter && typeof parameter === "object" && !Array.isArray(parameter) && parameter.name === name);
        if (matches.length !== 1) fail("Cloud Console build parameters differ from the fixed deployment target");
        exactKeys(matches[0], ["name", "value"], `Cloud Console build parameter ${name}`);
        if (matches[0].value !== value) fail("Cloud Console build parameters differ from the fixed deployment target");
    }

    exactKeys(buildType.steps, ["step"], "Cloud Console steps");
    const steps = requireArray(buildType.steps.step, "Cloud Console steps");
    if (steps.length !== 1) fail("Cloud Console build type must have exactly one Skaffold runner");
    exactKeys(steps[0], ["type", "properties"], "Cloud Console Skaffold runner");
    if (steps[0].type !== "simpleRunner") fail("Cloud Console Skaffold runner type differs");
    const runnerProperties = properties(steps[0].properties, "Cloud Console Skaffold runner properties");
    const scripts = runnerProperties.filter(property => property.name === "script.content");
    if (scripts.length !== 1 || typeof scripts[0].value !== "string") fail("Cloud Console Skaffold script is missing or ambiguous");
    const workingDirectories = runnerProperties.filter(property => property.name === "teamcity.build.workingDir");
    const customScripts = runnerProperties.filter(property => property.name === "use.custom.script");
    if (workingDirectories.length !== 1 || workingDirectories[0].value !== "webapp" || customScripts.length !== 1 || customScripts[0].value !== "true") fail("Cloud Console Skaffold runner mode or working directory is unsafe");
    const expectedAssignment = 'export SKAFFOLD_OPTIONS="--assume-yes=true -n ${K8S_NAMESPACE} --status-check -p deploy_only_production --cache-artifacts=false"';
    const assignments = scripts[0].value.split(/\r?\n/).map(line => line.trim()).filter(line => line.includes("SKAFFOLD_OPTIONS="));
    if (assignments.length !== 1 || assignments[0] !== expectedAssignment) fail("Cloud Console Skaffold options assignment is unsafe");
    if (!/\bskaffold(?:_\d+(?:\.\d+)*)?\s+run\s+\$\{SKAFFOLD_OPTIONS\}(?:\s|$)/.test(scripts[0].value)) fail("Cloud Console Skaffold script does not run its validated options");
    const profiles = [...scripts[0].value.matchAll(/(?:^|\s)(?:-p|--profile)(?:=|\s+)([^\s"'\\]+)/g)].map(match => match[1]);
    if (profiles.length !== 1 || profiles[0] !== "deploy_only_production") fail("Cloud Console Skaffold profile is unsafe");
    if (/\bskaffold(?:_\d+(?:\.\d+)*)?\b[^\r\n;&|]*\s(?:-f|--filename)(?:=|\s)/m.test(scripts[0].value)) fail("Cloud Console Skaffold filename override is unsafe");
    if (/\bdocker\b[^\r\n;&|]*(?:\bbuild(?:x)?\b|\bpush\b)|\bskaffold(?:_\d+(?:\.\d+)*)?\b[^\r\n;&|]*(?:\bbuild\b|\bpush\b)/i.test(scripts[0].value)) fail("Cloud Console script contains a build or push command");
    if (normalizeGeneratedScript(scripts[0].value) !== approvedGeneratedScript) fail("Cloud Console Skaffold script differs from the approved normalized template");
    const skaffoldExecutables = scripts[0].value.replaceAll("\r\n", "\n").split("\n").map(line => line.trim())
        .map(line => /^(skaffold(?:_\d+(?:\.\d+)*)?) (?:config set --global collect-metrics false|run \$\{SKAFFOLD_OPTIONS\} 2>&1)$/.exec(line)?.[1])
        .filter(Boolean);
    if (skaffoldExecutables.length !== 4 || skaffoldExecutables[0] !== skaffoldExecutables[1] || skaffoldExecutables[2] !== skaffoldExecutables[3]) fail("Cloud Console Skaffold config/run executable pair differs");
    exactKeys(buildType.triggers, ["trigger"], "Cloud Console triggers");
    const triggers = requireArray(buildType.triggers.trigger, "Cloud Console triggers");
    triggers.forEach((trigger, index) => { exactKeys(trigger, ["id", "type", "disabled"], `Cloud Console trigger ${index}`); if (trigger.disabled !== true) fail("Cloud Console build type has an enabled trigger"); });

    exactKeys(vcsRoot, ["id", "properties"], "Cloud Console VCS root");
    if (vcsRoot.id !== config.vcsRootId) fail("Cloud Console VCS root ID differs");
    const vcsRootProperties = properties(vcsRoot.properties, "Cloud Console VCS root properties");
    const branchSpecs = vcsRootProperties.filter(property => property.name === "teamcity:branchSpec");
    if (branchSpecs.length !== 1 || branchSpecs[0].value !== "+:refs/heads/(release/webapp/*)") fail("Cloud Console VCS root branch specification is unsafe");
    const urls = vcsRootProperties.filter(property => property.name === "url");
    if (urls.length !== 1 || urls[0].value !== approvedVcsUrl) fail("Cloud Console VCS root URL differs from the approved repository");
}

export function resolveVcsRootInstance(value, config) {
    exactKeys(value, ["vcs-root-instance"], "Cloud Console VCS root instances");
    const instances = value["vcs-root-instance"];
    if (!Array.isArray(instances) || instances.length !== 1) fail("Cloud Console VCS root must resolve to exactly one instance");
    exactKeys(instances[0], ["id", "vcs-root-id"], "Cloud Console VCS root instance");
    const instanceId = String(instances[0].id ?? "");
    if (instances[0]["vcs-root-id"] !== config.vcsRootId || !/^\d+$/.test(instanceId)) fail("Cloud Console VCS root instance mapping differs");
    return instanceId;
}

export function createQueueRequest(config, evidence, vcsRootInstanceId) {
    const payload = { buildType: { id: config.buildTypeId }, branchName: evidence.branch, revisions: { revision: [{ version: evidence.revision, "vcs-root-instance": { id: vcsRootInstanceId } }] }, properties: { property: [{ name: "env.HEXANA_WEBAPP_IMAGE", value: evidence.image }] } };
    return { url: `${config.serverUrl}/app/rest/buildQueue`, init: { method: "POST", headers: { Accept: "application/json", "Content-Type": "application/json", Authorization: `Bearer ${config.token}` }, body: JSON.stringify(payload) } };
}

const normalizeBranch = value => typeof value === "string" && value.startsWith("refs/heads/") ? value.slice(11) : value;
function validateBuild(value, config, evidence, vcsRootInstanceId, buildId) {
    object(value, "Cloud Console build");
    if (value.buildType?.id !== config.buildTypeId || normalizeBranch(value.branchName) !== evidence.branch) fail("Cloud Console build target or branch differs");
    const revisions = value.revisions?.revision;
    if (!Array.isArray(revisions) || revisions.length !== 1 || revisions[0]?.version !== evidence.revision || revisions[0]?.["vcs-root-instance"]?.id !== vcsRootInstanceId) fail("Cloud Console build revision differs");
    if (value.id !== buildId || !["queued", "running", "finished"].includes(value.state)) fail("Cloud Console build identity or state is unsupported");
    return { finished: value.state === "finished", status: value.status };
}

async function requestJson(fetchImpl, url, init, timeoutMs) {
    let response;
    try { response = await fetchImpl(url, { ...init, redirect: "error", signal: AbortSignal.timeout(Math.max(1, Math.min(30_000, timeoutMs))) }); } catch { fail("TeamCity request failed"); }
    if (!response?.ok) fail(`TeamCity returned HTTP ${response?.status ?? "unknown"}`);
    try { return await response.json(); } catch { fail("TeamCity response is not JSON"); }
}

const buildStatusUrl = (config, buildId) => `${config.serverUrl}/app/rest/builds/id:${buildId}?fields=id,state,status,branchName,buildType(id),revisions(revision(version,vcs-root-instance(id)))`;

const iso = milliseconds => new Date(milliseconds).toISOString();
const safeMessage = (error, token) => {
    const original = error instanceof Error ? error.message : "Unexpected deployment failure";
    return (token ? original.replaceAll(token, "[redacted]") : original).slice(0, 500);
};
const readinessFailure = reason => ({ ready: false, reason });
const expired = (deadline, now) => deadline - now() <= 0;

const cancelReader = reader => {
    try { reader.cancel().catch(() => {}); } catch {}
};

async function readBoundedBody(response, limit, deadline, now) {
    const contentLength = response.headers?.get?.("content-length");
    if (/^\d+$/.test(contentLength ?? "") && Number(contentLength) > limit) {
        try { response.body?.cancel?.().catch(() => {}); } catch {}
        return { error: `body exceeded ${limit}-byte limit` };
    }
    if (!response.body) return { bytes: 0, text: "" };
    if (typeof response.body.getReader !== "function") return { error: "body stream was unavailable" };
    const reader = response.body.getReader();
    const chunks = [];
    let total = 0;
    try {
        while (true) {
            const remaining = deadline - now();
            if (remaining <= 0) {
                cancelReader(reader);
                return { error: "shared readiness deadline elapsed" };
            }
            let timer;
            const timeout = new Promise(resolveTimeout => { timer = setTimeout(() => resolveTimeout(null), remaining); });
            let result;
            try { result = await Promise.race([reader.read(), timeout]); }
            catch { cancelReader(reader); return { error: "body could not be read" }; }
            finally { clearTimeout(timer); }
            if (!result) {
                cancelReader(reader);
                return { error: "shared readiness deadline elapsed" };
            }
            if (expired(deadline, now)) {
                cancelReader(reader);
                return { error: "shared readiness deadline elapsed" };
            }
            if (result.done) break;
            const chunk = result.value instanceof Uint8Array ? result.value : new Uint8Array(result.value);
            total += chunk.byteLength;
            if (total > limit) {
                cancelReader(reader);
                return { error: `body exceeded ${limit}-byte limit` };
            }
            chunks.push(chunk);
        }
    } finally {
        try { reader.releaseLock(); } catch {}
    }
    if (expired(deadline, now)) return { error: "shared readiness deadline elapsed" };
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    try { return { bytes: total, text: new TextDecoder("utf-8", { fatal: true }).decode(bytes) }; }
    catch { return { error: "body was not valid UTF-8" }; }
}

async function readinessResponse(fetchImpl, url, deadline, now) {
    const remaining = deadline - now();
    if (remaining <= 0) return { error: "shared readiness deadline elapsed" };
    try {
        const response = await fetchImpl(url, {
            redirect: "manual",
            headers: { "Cache-Control": "no-cache", Pragma: "no-cache" },
            signal: AbortSignal.timeout(Math.max(1, Math.min(10_000, remaining))),
        });
        if (expired(deadline, now)) return { error: "shared readiness deadline elapsed" };
        const expectedOrigin = new URL(url).origin;
        if (!response || (response.status >= 300 && response.status < 400)) return { error: "redirect refused" };
        if (response.url && new URL(response.url).origin !== expectedOrigin) return { error: "cross-origin response refused" };
        return { response };
    } catch {
        return { error: "request failed" };
    }
}

async function probeProductionEndpoint(fetchImpl, endpoint, evidence, attempt, deadline, now) {
    const healthUrl = new URL("/healthz", endpoint).href;
    const healthResult = await readinessResponse(fetchImpl, healthUrl, deadline, now);
    if (healthResult.error) return readinessFailure(`health ${healthResult.error}`);
    const health = healthResult.response;
    if (health.status !== 204) return readinessFailure(`health returned HTTP ${health.status}`);
    if (health.headers?.get?.("cache-control") !== "no-store") return readinessFailure("health Cache-Control was not no-store");
    const healthBody = await readBoundedBody(health, readinessBodyLimits.healthBytes, deadline, now);
    if (healthBody.error) return readinessFailure(`health ${healthBody.error}`);
    if (expired(deadline, now)) return readinessFailure("health shared readiness deadline elapsed");

    const rootUrl = new URL("/", endpoint);
    rootUrl.searchParams.set("hexana-readiness", `${evidence.revision}-${attempt}`);
    const rootResult = await readinessResponse(fetchImpl, rootUrl.href, deadline, now);
    if (rootResult.error) return readinessFailure(`root ${rootResult.error}`);
    const root = rootResult.response;
    if (root.status !== 200) return readinessFailure(`root returned HTTP ${root.status}`);
    const rootBody = await readBoundedBody(root, readinessBodyLimits.rootBytes, deadline, now);
    if (rootBody.error) return readinessFailure(`root ${rootBody.error}`);
    if (expired(deadline, now)) return readinessFailure("root shared readiness deadline elapsed");
    const html = rootBody.text;
    const match = /<script\b(?=[^>]*\bid=["']hexana-release-info["'])[^>]*>([\s\S]*?)<\/script>/i.exec(html);
    if (!match) return readinessFailure("release identity was missing");
    let identity;
    try { identity = parseJsonStrict(match[1], "release identity"); } catch { return readinessFailure("release identity was invalid"); }
    if (identity?.schemaVersion !== 1 || identity?.product !== "hexana-webapp" || identity?.version !== evidence.version || identity?.revision !== evidence.revision) return readinessFailure("release identity did not match the approved version and revision");
    return { ready: true, identity: { version: identity.version, revision: identity.revision } };
}

export async function writeDeploymentEvidenceAtomic(outputDirectory, evidence) {
    const output = resolve(outputDirectory, "deployment-evidence.json");
    await mkdir(outputDirectory, { recursive: true });
    const temporary = resolve(output + `.${randomUUID()}.tmp`);
    try {
        await writeFile(temporary, `${JSON.stringify(evidence)}\n`, { flag: "wx" });
        await rename(temporary, output);
    } finally {
        await rm(temporary, { force: true });
    }
}

export async function deployRelease({ receipt, scan, config, outputDirectory, fetchImpl = fetch, sleep = ms => new Promise(done => setTimeout(done, ms)), now = Date.now }) {
    const startedAtMs = now();
    let phase = "validation";
    let queueAttempted = false;
    let exactBuildFinished = false;
    let attempt = {
        schemaVersion: 1,
        kind: "hexana-webapp-deployment-evidence",
        result: "FAILURE",
        phase,
        profile: "deploy_only_production",
        digestHttpProven: false,
        release: { branch: config.branch, version: receipt?.package?.identity?.version, revision: config.revision },
        image: { reference: receipt?.image?.reference, manifestDigest: receipt?.image?.manifestDigest },
        sourceBuilds: Object.fromEntries(Object.entries(config.sourceBuilds).filter(([, value]) => value !== undefined)),
        cloudConsole: { buildTypeId: config.buildTypeId, vcsRootId: config.vcsRootId, queueAttempted: false, queueAmbiguous: false, exactBuildFinished: false, lastExactObservation: null },
        deadlines: { cloudConsoleBuild: null, productionReadiness: null },
        readiness: { endpoints: productionEndpoints.map(url => ({ url, ready: false, attempts: 0, lastObservation: null })) },
        startedAt: iso(startedAtMs),
        finishedAt: null,
        failure: null,
    };
    try {
        validatePinnedTarget(config);
        const evidence = validateEvidence(receipt, scan, config.branch, config.revision);
        attempt.release = { branch: evidence.branch, version: evidence.version, revision: evidence.revision };
        attempt.image = { reference: evidence.image, manifestDigest: evidence.digest };

        phase = "preflight";
        const preflightRequests = createSafetyPreflightRequests(config);
        const [buildType, vcsRoot] = await Promise.all(preflightRequests.map(request => requestJson(fetchImpl, request.url, request.init, 30_000)));
        validateSafetyPreflight(buildType, vcsRoot, config);
        const rootRequest = createVcsRootInstancesRequest(config);
        const vcsRootInstanceId = resolveVcsRootInstance(await requestJson(fetchImpl, rootRequest.url, rootRequest.init, 30_000), config);
        attempt.cloudConsole.vcsRootInstanceId = vcsRootInstanceId;
        const queued = createQueueRequest(config, evidence, vcsRootInstanceId);
        phase = "queue";
        queueAttempted = true;
        attempt.cloudConsole.queueAttempted = true;
        attempt.cloudConsole.queueAmbiguous = true;
        const queueResponse = await requestJson(fetchImpl, queued.url, queued.init, 30_000);
        const buildId = queueResponse?.id;
        if (!Number.isInteger(buildId) || buildId <= 0) fail("Cloud Console queue response has no positive build ID");
        attempt.cloudConsole.queueAmbiguous = false;
        const buildUrl = `${config.serverUrl}/viewLog.html?buildId=${buildId}`;
        attempt.cloudConsole.buildId = buildId;
        attempt.cloudConsole.buildUrl = buildUrl;
        attempt.cloudConsole.pollAttempts = 0;

        phase = "cloud-console-build";
        const buildStartedAtMs = now();
        const buildDeadline = buildStartedAtMs + config.buildTimeoutMs;
        attempt.deadlines.cloudConsoleBuild = { startedAt: iso(buildStartedAtMs), deadlineAt: iso(buildDeadline), timeoutMs: config.buildTimeoutMs };
        while (true) {
            const remaining = buildDeadline - now();
            if (remaining <= 0) {
                fail(`Cloud Console build ${buildId} timed out after ${config.buildTimeoutMs} ms; it was not canceled and may continue at ${buildUrl}`);
            }
            const build = await requestJson(fetchImpl, buildStatusUrl(config, buildId), { headers: { Accept: "application/json", Authorization: `Bearer ${config.token}` } }, remaining);
            if (expired(buildDeadline, now)) fail(`Cloud Console build ${buildId} timed out after ${config.buildTimeoutMs} ms; it was not canceled and may continue at ${buildUrl}`);
            attempt.cloudConsole.pollAttempts++;
            const observation = validateBuild(build, config, evidence, vcsRootInstanceId, buildId);
            attempt.cloudConsole.lastExactObservation = { at: iso(now()), state: build.state, status: build.status ?? null };
            if (observation.finished) {
                exactBuildFinished = true;
                attempt.cloudConsole.exactBuildFinished = true;
                if (observation.status !== "SUCCESS") fail(`Cloud Console deployment finished with ${observation.status ?? "unknown status"}`);
                break;
            }
            const afterRequest = buildDeadline - now();
            if (afterRequest <= 0) {
                fail(`Cloud Console build ${buildId} timed out after ${config.buildTimeoutMs} ms; it was not canceled and may continue at ${buildUrl}`);
            }
            await sleep(Math.min(config.pollIntervalMs, afterRequest));
        }

        phase = "production-readiness";
        const readinessStartedAtMs = now();
        const readinessDeadline = readinessStartedAtMs + config.readinessTimeoutMs;
        attempt.deadlines.productionReadiness = { startedAt: iso(readinessStartedAtMs), deadlineAt: iso(readinessDeadline), timeoutMs: config.readinessTimeoutMs };
        while (true) {
            if (readinessDeadline - now() <= 0) fail(`Production readiness did not converge on both endpoints within ${config.readinessTimeoutMs} ms`);
            const results = await Promise.all(productionEndpoints.map((endpoint, index) => probeProductionEndpoint(fetchImpl, endpoint, evidence, attempt.readiness.endpoints[index].attempts + 1, readinessDeadline, now)));
            const observedAt = iso(now());
            results.forEach((result, index) => {
                const endpoint = attempt.readiness.endpoints[index];
                endpoint.attempts++;
                endpoint.ready = result.ready;
                endpoint.lastObservation = result.ready ? { at: observedAt, status: "ready", identity: result.identity } : { at: observedAt, status: "retry", reason: result.reason };
            });
            if (expired(readinessDeadline, now)) fail(`Production readiness did not converge on both endpoints within ${config.readinessTimeoutMs} ms`);
            if (results.every(result => result.ready)) break;
            const remaining = readinessDeadline - now();
            if (remaining <= 0) fail(`Production readiness did not converge on both endpoints within ${config.readinessTimeoutMs} ms`);
            await sleep(Math.min(config.pollIntervalMs, remaining));
        }

        attempt.result = "SUCCESS";
        attempt.phase = "complete";
        attempt.finishedAt = iso(now());
        phase = "evidence";
        if (outputDirectory) await writeDeploymentEvidenceAtomic(outputDirectory, attempt);
        return attempt;
    } catch (error) {
        const message = safeMessage(error, config.token);
        attempt.result = "FAILURE";
        attempt.phase = phase;
        attempt.finishedAt = iso(now());
        attempt.failure = { message, buildMayContinue: queueAttempted && !exactBuildFinished };
        let evidenceWriteFailure;
        if (outputDirectory) {
            try { await writeDeploymentEvidenceAtomic(outputDirectory, attempt); }
            catch (writeError) { evidenceWriteFailure = safeMessage(writeError, config.token); }
        }
        if (evidenceWriteFailure) attempt.failure.evidenceWriteFailure = evidenceWriteFailure;
        const failure = new Error(evidenceWriteFailure ? `${message}; deployment evidence write failed: ${evidenceWriteFailure}` : message);
        failure.deploymentEvidence = attempt;
        failure.evidenceWritten = Boolean(outputDirectory) && !evidenceWriteFailure;
        throw failure;
    }
}

function options(args) {
    const result = new Map();
    for (let index = 0; index < args.length; index += 2) {
        if (!args[index]?.startsWith("--") || args[index + 1] === undefined || result.has(args[index])) fail("options must be unique key/value pairs");
        result.set(args[index], args[index + 1]);
    }
    if (result.size !== 3 || !["--receipt", "--scan-summary", "--output-dir"].every(key => result.has(key))) fail("expected --receipt, --scan-summary, and --output-dir");
    return result;
}

async function main() {
    const input = options(process.argv.slice(2));
    const outputDirectory = resolve(input.get("--output-dir"));
    await mkdir(outputDirectory, { recursive: true });
    const startedAtMs = Date.now();
    let receipt;
    try {
        const config = configFromEnv(process.env);
        receipt = await readFile(input.get("--receipt"), "utf8").then(value => parseJsonStrict(value, "Build receipt"));
        const scan = await readFile(input.get("--scan-summary"), "utf8").then(value => parseJsonStrict(value, "Scan summary"));
        await deployRelease({ receipt, scan, config, outputDirectory });
    } catch (error) {
        const message = safeMessage(error, process.env.HEXANA_CLOUD_CONSOLE_TOKEN);
        if (!error?.evidenceWritten) {
            await writeDeploymentEvidenceAtomic(outputDirectory, error?.deploymentEvidence ?? {
                schemaVersion: 1,
                kind: "hexana-webapp-deployment-evidence",
                result: "FAILURE",
                phase: "input",
                profile: "deploy_only_production",
                digestHttpProven: false,
                release: { branch: process.env.HEXANA_RELEASE_BRANCH, version: receipt?.package?.identity?.version, revision: process.env.HEXANA_RELEASE_REVISION },
                image: { reference: receipt?.image?.reference, manifestDigest: receipt?.image?.manifestDigest },
                sourceBuilds: {},
                cloudConsole: { buildTypeId: process.env.HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID, vcsRootId: process.env.HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID, queueAttempted: false, queueAmbiguous: false, exactBuildFinished: false, lastExactObservation: null },
                deadlines: { cloudConsoleBuild: null, productionReadiness: null },
                readiness: { endpoints: productionEndpoints.map(url => ({ url, ready: false, attempts: 0, lastObservation: null })) },
                startedAt: iso(startedAtMs),
                finishedAt: iso(Date.now()),
                failure: { message, buildMayContinue: false },
            });
        }
        throw new Error(message);
    }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error(error.message); process.exitCode = 2; });

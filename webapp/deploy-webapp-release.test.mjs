import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { allowedTarget, configFromEnv, createQueueRequest, createSafetyPreflightRequests, createVcsRootInstancesRequest, deployRelease, deploymentTiming, parseJsonStrict, productionEndpoints, readinessBodyLimits, resolveVcsRootInstance, validateEvidence, validateSafetyPreflight } from "./deploy-webapp-release.mjs";

const revision = "0123456789abcdef0123456789abcdef01234567";
const digest = `sha256:${"a".repeat(64)}`;
const config = { ...allowedTarget, token: "secret", branch: "release/webapp/2.7", revision, buildTimeoutMs: 1_000, readinessTimeoutMs: 1_000, pollIntervalMs: 100, sourceBuilds: { release: 10, build: 11, scan: 12 } };
const vcsRootInstanceId = "125306";
const evidenceFiles = ["build-identity.json", "checksums.sha256", "package-manifest.json", "provenance.json", "release-info.json", "resolved-dependencies.json", "sbom.cdx.json"];
function fixture() {
    const image = `registry.jetbrains.team/p/hx/containers/hexana-webapp@${digest}`;
    const receipt = { schemaVersion: 1, kind: "hexana-webapp-oci-evidence", releaseBranch: config.branch, sourceRevision: revision, image: { reference: image, manifestDigest: digest, configDigest: `sha256:${"b".repeat(64)}`, manifestMediaType: "application/vnd.oci.image.manifest.v1+json", configMediaType: "application/vnd.oci.image.config.v1+json", platform: { os: "linux", architecture: "amd64" } }, package: { identity: { schemaVersion: 1, product: "hexana-webapp", version: "2.7.3", versionSource: "webapp/gradle.properties", revision }, aggregateSha256: "c".repeat(64), evidence: Object.fromEntries(evidenceFiles.map(name => [name, "d".repeat(64)])) } };
    const scan = { schemaVersion: 1, kind: "hexana-webapp-vulnerability-evidence", decision: "pass", evaluatedAt: "2026-01-01T00:00:00.000Z", release: { branch: config.branch, revision, version: "2.7.3" }, image: { reference: image, manifestDigest: digest, configDigest: receipt.image.configDigest, archiveSha256: "e".repeat(64) }, sbomSha256: "f".repeat(64), scanner: {}, database: {}, reports: {}, counts: { UNKNOWN: 0, LOW: 1, MEDIUM: 0, HIGH: 0, CRITICAL: 0 }, findings: [{ severity: "LOW" }] };
    return { receipt, scan };
}
const response = value => ({ ok: true, status: 200, json: async () => value });
const bytes = value => new TextEncoder().encode(value);
const finiteStream = (value, cancel) => new ReadableStream({ start(controller) { controller.enqueue(bytes(value)); controller.close(); }, cancel });
const healthResponse = (url, status = 204, body = "", cacheControl = "no-store") => {
    const headers = new Headers();
    if (cacheControl !== null) headers.set("Cache-Control", cacheControl);
    return { status, url, headers, body: body ? finiteStream(body) : null };
};
const releaseHtml = (version = "2.7.3", sourceRevision = revision) => `<html><script id="hexana-release-info" type="application/json">${JSON.stringify({ schemaVersion: 1, product: "hexana-webapp", version, revision: sourceRevision })}</script></html>`;
const rootResponse = (url, version, sourceRevision) => {
    const result = new Response(releaseHtml(version, sourceRevision), { status: 200 });
    Object.defineProperty(result, "url", { value: url });
    return result;
};
const readinessResponse = (url, overrides = {}) => new URL(url).pathname === "/healthz" ? healthResponse(url, overrides.healthStatus, overrides.healthBody, overrides.cacheControl) : rootResponse(url, overrides.version, overrides.revision);
const deploymentFetch = (teamCityValues, { readiness = readinessResponse, calls = [] } = {}) => async (url, init) => {
    calls.push({ url, init });
    if (productionEndpoints.some(endpoint => new URL(url).origin === new URL(endpoint).origin)) return readiness(url, init);
    return response(teamCityValues.shift());
};
const validEnvironment = overrides => ({
    TEAMCITY_SERVER_URL: allowedTarget.serverUrl,
    HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID: allowedTarget.buildTypeId,
    HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID: allowedTarget.vcsRootId,
    HEXANA_RELEASE_BRANCH: config.branch,
    HEXANA_RELEASE_REVISION: revision,
    HEXANA_CLOUD_CONSOLE_TOKEN: "secret",
    ...overrides,
});
const attemptFile = directory => readFile(join(directory, "deployment-evidence.json"), "utf8").then(JSON.parse);
const capturedFailure = async promise => {
    try { await promise; } catch (error) { return error; }
    assert.fail("expected deployment failure");
};
const safeScript = `
#!/bin/bash
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
fi
`;
const safeParameters = () => [
    { name: "env.APPLICATION_NAME", value: "hexana-webapp" },
    { name: "env.K8S_NAMESPACE", value: "hexana-namespace" },
    { name: "env.IMAGE_PULL_SECRET", value: "registry-pull-secret" },
    { name: "env.JBCC_GCP_CLUSTER", value: "example-cluster" },
    { name: "env.JBCC_GCP_PROJECT", value: "example-gcp-project" },
    { name: "env.JBCC_GCP_REGION", value: "europe-west1" },
    { name: "unrelated.password.parameter" },
];
const safeBuildType = () => ({ id: config.buildTypeId, "vcs-root-entries": { "vcs-root-entry": [{ "vcs-root": { id: config.vcsRootId } }] }, steps: { step: [{ type: "simpleRunner", properties: { property: [{ name: "script.content", value: safeScript }, { name: "teamcity.build.workingDir", value: "webapp" }, { name: "use.custom.script", value: "true" }] } }] }, triggers: { trigger: [{ id: "TRIGGER_1", type: "vcsTrigger", disabled: true }] }, parameters: { property: safeParameters() } });
const safeVcsRoot = () => ({ id: config.vcsRootId, properties: { property: [{ name: "teamcity:branchSpec", value: "+:refs/heads/(release/webapp/*)" }, { name: "url", value: "https://example.invalid/hexana/Hexana.git" }] } });
const buildParameter = (value, name) => value.parameters.property.find(parameter => parameter.name === name);
const rootInstances = (overrides = {}) => ({ "vcs-root-instance": [{ id: vcsRootInstanceId, "vcs-root-id": config.vcsRootId }], ...overrides });
const build = (state, overrides = {}) => ({ id: 42, state, status: state === "finished" ? "SUCCESS" : undefined, branchName: `refs/heads/${config.branch}`, buildType: { id: config.buildTypeId }, revisions: { revision: [{ version: revision, vcsBranchName: `refs/heads/${config.branch}`, "vcs-root-instance": { id: vcsRootInstanceId } }] }, ...overrides });

test("validates same-series immutable Build and passing Scan evidence", () => {
    const { receipt, scan } = fixture();
    assert.deepEqual(validateEvidence(receipt, scan, config.branch, revision), { branch: config.branch, revision, version: "2.7.3", image: receipt.image.reference, digest });
    assert.throws(() => validateEvidence(receipt, scan, "main", revision), /selected branch/);
    assert.throws(() => parseJsonStrict('{"a":1,"a":2}', "fixture"), /duplicate key/);
});

test("uses one-hour build and five-minute readiness defaults with hard upper bounds", () => {
    assert.deepEqual(productionEndpoints, ["https://hexana-webapp.example.invalid/"]);
    const parsed = configFromEnv(validEnvironment());
    assert.equal(parsed.buildTimeoutMs, deploymentTiming.buildTimeoutMs);
    assert.equal(parsed.readinessTimeoutMs, deploymentTiming.readinessTimeoutMs);
    assert.equal(parsed.pollIntervalMs, deploymentTiming.pollIntervalMs);
    assert.throws(() => configFromEnv(validEnvironment({ HEXANA_DEPLOY_TIMEOUT_MS: String(deploymentTiming.buildTimeoutMs + 1) })), /HEXANA_DEPLOY_TIMEOUT_MS/);
    assert.throws(() => configFromEnv(validEnvironment({ HEXANA_READINESS_TIMEOUT_MS: String(deploymentTiming.readinessTimeoutMs + 1) })), /HEXANA_READINESS_TIMEOUT_MS/);
    assert.throws(() => configFromEnv(validEnvironment({ HEXANA_DEPLOY_TIMEOUT_MS: "1000", HEXANA_READINESS_TIMEOUT_MS: "1000", HEXANA_DEPLOY_POLL_INTERVAL_MS: "1000" })), /shorter than both/);
});

for (const [name, mutate, expected] of [
    ["wrong branch", (receipt, scan) => { receipt.releaseBranch = "release/webapp/2.8"; }, /source identity/],
    ["wrong version", (receipt) => { receipt.package.identity.version = "2.8.0"; }, /release series/],
    ["wrong revision", (receipt) => { receipt.sourceRevision = "f".repeat(40); }, /source identity/],
    ["different scan digest", (_receipt, scan) => { scan.image.manifestDigest = `sha256:${"f".repeat(64)}`; }, /differs from Build/],
    ["failing scan", (_receipt, scan) => { scan.decision = "fail"; }, /not a passing/],
    ["high finding", (_receipt, scan) => { scan.counts.HIGH = 1; }, /zero HIGH/],
    ["mutable image", (receipt, scan) => { receipt.image.reference = scan.image.reference = "registry.jetbrains.team/p/hx/containers/hexana-webapp:2.7.3"; }, /canonical immutable/],
]) test(`rejects ${name}`, () => { const { receipt, scan } = fixture(); mutate(receipt, scan); assert.throws(() => validateEvidence(receipt, scan, config.branch, revision), expected); });

test("creates the fixed queue request with only the validated image parameter", () => {
    const validated = validateEvidence(...Object.values(fixture()), config.branch, revision);
    const rootRequest = createVcsRootInstancesRequest(config);
    assert.equal(rootRequest.url, `${allowedTarget.serverUrl}/app/rest/vcs-root-instances?locator=vcsRoot:(id:${allowedTarget.vcsRootId})&fields=vcs-root-instance(id,vcs-root-id)`);
    assert.equal(resolveVcsRootInstance(rootInstances(), config), vcsRootInstanceId);
    const request = createQueueRequest(config, validated, vcsRootInstanceId);
    assert.equal(request.url, `${allowedTarget.serverUrl}/app/rest/buildQueue?fields=id,state,status,branchName,buildType(id),revisions(revision(version,vcsBranchName,vcs-root-instance(id)))`);
    assert.equal(JSON.parse(request.init.body).branchName, "release/webapp/2.7");
    assert.deepEqual(JSON.parse(request.init.body).properties.property, [{ name: "env.HEXANA_WEBAPP_IMAGE", value: validated.image }]);
    assert.equal(JSON.parse(request.init.body).revisions.revision[0].version, revision);
    assert.equal(JSON.parse(request.init.body).revisions.revision[0].vcsBranchName, "refs/heads/release/webapp/2.7");
    assert.deepEqual(JSON.parse(request.init.body).revisions.revision[0]["vcs-root-instance"], { id: vcsRootInstanceId });
    assert.equal(request.init.headers.Authorization, "Bearer secret");
});

test("uses explicit fields and accepts only the safe generated configuration", () => {
    const requests = createSafetyPreflightRequests(config);
    assert.match(requests[0].url, /fields=id,vcs-root-entries\(vcs-root-entry\(vcs-root\(id\)\)\),steps\(step\(type,properties\(property\(name,value\)\)\)\),triggers\(trigger\(id,type,disabled\)\),parameters\(property\(name,value\)\)$/);
    assert.match(requests[1].url, /fields=id,properties\(property\(name,value\)\)$/);
    assert.doesNotThrow(() => validateSafetyPreflight(safeBuildType(), safeVcsRoot(), config));
});

test("accepts only irrelevant generated-script formatting differences", () => {
    const buildType = safeBuildType();
    let rawHeredocLines = 0;
    buildType.steps.step[0].properties.property[0].value = safeScript.split("\n").map(line => {
        if (rawHeredocLines > 0) { rawHeredocLines--; return line; }
        if (line.trim() === `cat > "$KUBECONFIG" <<'EOF'`) rawHeredocLines = 2;
        return line ? `  ${line}  ` : "";
    }).join("\r\n");
    assert.doesNotThrow(() => validateSafetyPreflight(buildType, safeVcsRoot(), config));
});

test("queues, polls, and returns secret-free deployment evidence", async () => {
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("running"), build("finished")];
    const calls = [];
    let clock = 0;
    const result = await deployRelease({ ...fixture(), config, fetchImpl: deploymentFetch(values, { calls }), sleep: async ms => { clock += ms; }, now: () => clock });
    assert.equal(result.cloudConsole.buildId, 42);
    assert.equal(result.cloudConsole.vcsRootInstanceId, vcsRootInstanceId);
    assert.match(calls.findLast(call => call.url.includes("/app/rest/builds/"))?.url, /fields=id,state,status,branchName,buildType\(id\),revisions\(revision\(version,vcsBranchName,vcs-root-instance\(id\)\)\)$/);
    assert.ok(calls.filter(call => call.url.startsWith(config.serverUrl)).every(call => call.init.redirect === "error"));
    assert.ok(calls.filter(call => !call.url.startsWith(config.serverUrl)).every(call => call.init.redirect === "manual"));
    assert.deepEqual(result.sourceBuilds, config.sourceBuilds);
    assert.equal(result.digestHttpProven, false);
    assert.equal(result.cloudConsole.queueAttempted, true);
    assert.equal(result.cloudConsole.queueAmbiguous, false);
    assert.equal(result.cloudConsole.exactBuildFinished, true);
    assert.equal(result.cloudConsole.lastExactObservation.state, "finished");
    assert.ok(result.readiness.endpoints.every(endpoint => endpoint.ready));
    assert.equal(JSON.stringify(result).includes(config.token), false);
});

test("does not probe production before the exact Cloud Console build succeeds", async () => {
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("queued"), build("running"), build("finished")];
    const calls = [];
    let clock = 0;
    await deployRelease({ ...fixture(), config, fetchImpl: deploymentFetch(values, { calls }), sleep: async ms => { clock += ms; }, now: () => clock });
    const lastBuildPoll = calls.findLastIndex(call => call.url.includes("/app/rest/builds/"));
    const firstReadiness = calls.findIndex(call => productionEndpoints.some(endpoint => call.url.startsWith(endpoint)));
    assert.equal(calls.filter(call => call.url.includes("/app/rest/builds/")).length, 3);
    assert.ok(firstReadiness > lastBuildPoll);
});

test("rejects an otherwise successful Cloud Console poll that completes at the deadline", async () => {
    let clock = 0;
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")];
    const fetchImpl = async url => {
        const value = values.shift();
        if (url.includes("/app/rest/builds/")) return { ok: true, status: 200, json: async () => { clock = 1_000; return value; } };
        return response(value);
    };
    const error = await capturedFailure(deployRelease({ ...fixture(), config, fetchImpl, now: () => clock }));
    assert.match(error.message, /timed out/);
    assert.equal(error.deploymentEvidence.cloudConsole.exactBuildFinished, false);
    assert.equal(error.deploymentEvidence.failure.buildMayContinue, true);
});

test("rejects an otherwise valid readiness body that completes at the deadline", async () => {
    let clock = 0;
    const lateRoot = url => {
        let delivered = false;
        return {
            status: 200,
            url,
            headers: new Headers(),
            body: { getReader: () => ({
                read: async () => {
                    if (delivered) return { done: true };
                    delivered = true;
                    clock = 200;
                    return { done: false, value: bytes(releaseHtml()) };
                },
                cancel: async () => {},
                releaseLock: () => {},
            }) },
        };
    };
    const readiness = url => new URL(url).pathname === "/healthz" ? healthResponse(url) : lateRoot(url);
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")];
    const error = await capturedFailure(deployRelease({ ...fixture(), config: { ...config, readinessTimeoutMs: 200 }, fetchImpl: deploymentFetch(values, { readiness }), now: () => clock }));
    assert.equal(error.deploymentEvidence.phase, "production-readiness");
    assert.match(error.message, /within 200 ms/);
    assert.ok(error.deploymentEvidence.readiness.endpoints.every(endpoint => /deadline elapsed/.test(endpoint.lastObservation.reason)));
});

test("retries network, 5xx, and old identity observations until the release endpoint recovers", async () => {
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")];
    const healthAttempts = new Map();
    const rootAttempts = new Map();
    const readiness = async url => {
        const parsed = new URL(url);
        if (parsed.pathname === "/healthz") {
            const attempt = (healthAttempts.get(parsed.host) ?? 0) + 1;
            healthAttempts.set(parsed.host, attempt);
            if (attempt === 1) throw new Error("transient network failure");
            if (attempt === 2) return healthResponse(url, 503);
            return healthResponse(url);
        }
        const attempt = (rootAttempts.get(parsed.host) ?? 0) + 1;
        rootAttempts.set(parsed.host, attempt);
        return attempt === 1 ? rootResponse(url, "2.7.2", "f".repeat(40)) : rootResponse(url);
    };
    let clock = 0;
    const result = await deployRelease({ ...fixture(), config, fetchImpl: deploymentFetch(values, { readiness }), sleep: async ms => { clock += ms; }, now: () => clock });
    assert.deepEqual(result.readiness.endpoints.map(endpoint => endpoint.attempts), [4]);
    assert.ok(result.readiness.endpoints.every(endpoint => endpoint.lastObservation.status === "ready"));
});

test("requires the approved release endpoint under the readiness deadline", async () => {
    const directory = await mkdtemp(join(tmpdir(), "hexana-release-readiness-"));
    const calls = [];
    let clock = 0;
    const shortConfig = { ...config, readinessTimeoutMs: 200 };
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")];
    const readiness = url => healthResponse(url, 503);
    try {
        await assert.rejects(deployRelease({ ...fixture(), config: shortConfig, outputDirectory: directory, fetchImpl: deploymentFetch(values, { readiness, calls }), sleep: async ms => { clock += ms; }, now: () => clock }), /approved release endpoint/);
        const evidence = await attemptFile(directory);
        assert.equal(evidence.phase, "production-readiness");
        assert.equal(evidence.deadlines.productionReadiness.timeoutMs, 200);
        assert.equal(Date.parse(evidence.deadlines.productionReadiness.deadlineAt) - Date.parse(evidence.deadlines.productionReadiness.startedAt), 200);
        assert.deepEqual(evidence.readiness.endpoints.map(endpoint => endpoint.ready), [false]);
        assert.ok(productionEndpoints.every(endpoint => calls.some(call => call.url.startsWith(endpoint))));
    } finally {
        await rm(directory, { recursive: true, force: true });
    }
});

for (const [name, readiness, expectedReason] of [
    ["health HTTP 200", url => new URL(url).pathname === "/healthz" ? healthResponse(url, 200) : rootResponse(url), /health returned HTTP 200/],
    ["health without Cache-Control", url => new URL(url).pathname === "/healthz" ? healthResponse(url, 204, "", null) : rootResponse(url), /Cache-Control was not no-store/],
    ["health with wrong Cache-Control", url => new URL(url).pathname === "/healthz" ? healthResponse(url, 204, "", "max-age=0") : rootResponse(url), /Cache-Control was not no-store/],
    ["root redirect", url => new URL(url).pathname === "/healthz" ? healthResponse(url) : { status: 302, url, headers: new Headers(), body: null }, /root redirect refused/],
    ["cross-origin root response", url => new URL(url).pathname === "/healthz" ? healthResponse(url) : rootResponse("https://evil.example/"), /root cross-origin response refused/],
    ["wrong version", url => new URL(url).pathname === "/healthz" ? healthResponse(url) : rootResponse(url, "2.7.2", revision), /identity did not match/],
    ["wrong revision", url => new URL(url).pathname === "/healthz" ? healthResponse(url) : rootResponse(url, "2.7.3", "f".repeat(40)), /identity did not match/],
]) test(`readiness rejects ${name}`, async () => {
    let clock = 0;
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")];
    let failure;
    try {
        await deployRelease({ ...fixture(), config: { ...config, readinessTimeoutMs: 200 }, fetchImpl: deploymentFetch(values, { readiness }), sleep: async ms => { clock += ms; }, now: () => clock });
    } catch (error) {
        failure = error;
    }
    assert.match(failure?.message ?? "", /approved release endpoint/);
    assert.ok(failure.deploymentEvidence.readiness.endpoints.every(endpoint => expectedReason.test(endpoint.lastObservation.reason)));
});

test("cancels a health body on its first byte", async () => {
    let canceled = 0;
    const readiness = url => {
        if (new URL(url).pathname !== "/healthz") return rootResponse(url);
        const body = new ReadableStream({ start(controller) { controller.enqueue(bytes("x")); }, cancel() { canceled++; } });
        return { status: 204, url, headers: new Headers({ "Cache-Control": "no-store" }), body };
    };
    let clock = 0;
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")];
    const error = await capturedFailure(deployRelease({ ...fixture(), config: { ...config, readinessTimeoutMs: 200 }, fetchImpl: deploymentFetch(values, { readiness }), sleep: async ms => { clock += ms; }, now: () => clock }));
    assert.ok(error.deploymentEvidence.readiness.endpoints.every(endpoint => /0-byte limit/.test(endpoint.lastObservation.reason)));
    assert.ok(canceled >= productionEndpoints.length);
});

test("rejects oversized root Content-Length before reading and cancels the stream", async () => {
    let canceled = 0;
    const readiness = url => {
        if (new URL(url).pathname === "/healthz") return healthResponse(url);
        const body = new ReadableStream({ pull() {}, cancel() { canceled++; } });
        return { status: 200, url, headers: new Headers({ "Content-Length": String(readinessBodyLimits.rootBytes + 1) }), body };
    };
    let clock = 0;
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")];
    const error = await capturedFailure(deployRelease({ ...fixture(), config: { ...config, readinessTimeoutMs: 200 }, fetchImpl: deploymentFetch(values, { readiness }), sleep: async ms => { clock += ms; }, now: () => clock }));
    assert.ok(error.deploymentEvidence.readiness.endpoints.every(endpoint => /1048576-byte limit/.test(endpoint.lastObservation.reason)));
    assert.ok(canceled >= productionEndpoints.length);
});

test("cancels a streaming root body as soon as it exceeds one MiB", async () => {
    let canceled = 0;
    const readiness = url => {
        if (new URL(url).pathname === "/healthz") return healthResponse(url);
        const body = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(readinessBodyLimits.rootBytes + 1)); }, cancel() { canceled++; } });
        return { status: 200, url, headers: new Headers(), body };
    };
    let clock = 0;
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")];
    const error = await capturedFailure(deployRelease({ ...fixture(), config: { ...config, readinessTimeoutMs: 200 }, fetchImpl: deploymentFetch(values, { readiness }), sleep: async ms => { clock += ms; }, now: () => clock }));
    assert.ok(error.deploymentEvidence.readiness.endpoints.every(endpoint => /1048576-byte limit/.test(endpoint.lastObservation.reason)));
    assert.ok(canceled >= productionEndpoints.length);
});

test("cancels non-terminating root bodies at the shared readiness deadline", async () => {
    let canceled = 0;
    const readiness = url => {
        if (new URL(url).pathname === "/healthz") return healthResponse(url);
        const body = new ReadableStream({ pull() {}, cancel() { canceled++; } });
        return { status: 200, url, headers: new Headers(), body };
    };
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")];
    const startedAt = Date.now();
    const error = await capturedFailure(deployRelease({ ...fixture(), config: { ...config, readinessTimeoutMs: 25, pollIntervalMs: 5 }, fetchImpl: deploymentFetch(values, { readiness }) }));
    assert.match(error.message, /within 25 ms/);
    assert.ok(Date.now() - startedAt < 1_000);
    assert.equal(canceled, productionEndpoints.length);
});

test("writes secret-free atomic evidence for success and every deployment failure phase", async () => {
    const directory = await mkdtemp(join(tmpdir(), "hexana-readiness-evidence-"));
    const token = "unique-sensitive-bearer-token";
    const evidenceConfig = { ...config, token, buildTimeoutMs: 200, readinessTimeoutMs: 200 };
    const run = async (name, options) => {
        const outputDirectory = join(directory, name);
        let error;
        try { await deployRelease({ ...fixture(), config: evidenceConfig, outputDirectory, ...options }); } catch (caught) { error = caught; }
        const raw = await readFile(join(outputDirectory, "deployment-evidence.json"), "utf8");
        assert.equal(raw.includes(token), false);
        assert.equal((error?.message ?? "").includes(token), false);
        return { evidence: JSON.parse(raw), error };
    };
    try {
        const success = await run("success", { fetchImpl: deploymentFetch([safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")]), now: () => 0 });
        assert.equal(success.error, undefined);
        assert.equal(success.evidence.result, "SUCCESS");
        assert.equal(success.evidence.phase, "complete");

        const invalid = fixture();
        invalid.receipt.sourceRevision = "f".repeat(40);
        const validation = await run("validation", { ...invalid });
        assert.equal(validation.evidence.phase, "validation");

        const unsafeBuildType = safeBuildType();
        unsafeBuildType.triggers.trigger[0].disabled = false;
        const preflight = await run("preflight", { fetchImpl: async url => response(url.includes("/buildTypes/") ? unsafeBuildType : safeVcsRoot()) });
        assert.equal(preflight.evidence.phase, "preflight");

        const failureCalls = [];
        const buildFailure = await run("build-failure", { fetchImpl: deploymentFetch([safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished", { status: "FAILURE" })], { calls: failureCalls }), now: () => 0 });
        assert.equal(buildFailure.evidence.phase, "cloud-console-build");
        assert.equal(buildFailure.evidence.failure.buildMayContinue, false);
        assert.equal(failureCalls.some(call => productionEndpoints.some(endpoint => call.url.startsWith(endpoint))), false);

        let timeoutClock = 0;
        const timeoutCalls = [];
        const buildTimeout = await run("build-timeout", { fetchImpl: deploymentFetch([safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("running"), build("running")], { calls: timeoutCalls }), sleep: async ms => { timeoutClock += ms; }, now: () => timeoutClock });
        assert.equal(buildTimeout.evidence.phase, "cloud-console-build");
        assert.equal(buildTimeout.evidence.failure.buildMayContinue, true);
        assert.match(buildTimeout.evidence.failure.message, /not canceled and may continue/);
        assert.equal(buildTimeout.evidence.cloudConsole.buildId, 42);
        assert.match(buildTimeout.evidence.cloudConsole.buildUrl, /buildId=42/);
        assert.equal(timeoutCalls.some(call => productionEndpoints.some(endpoint => call.url.startsWith(endpoint))), false);

        let readinessClock = 0;
        const readiness = await run("readiness", { fetchImpl: deploymentFetch([safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished")], { readiness: url => healthResponse(url, 503) }), sleep: async ms => { readinessClock += ms; }, now: () => readinessClock });
        assert.equal(readiness.evidence.phase, "production-readiness");
        assert.equal(readiness.evidence.digestHttpProven, false);
    } finally {
        await rm(directory, { recursive: true, force: true });
    }
});

test("preserves the deployment failure when atomic evidence cannot be written", async () => {
    const directory = await mkdtemp(join(tmpdir(), "hexana-readiness-evidence-write-failure-"));
    const blockedOutput = join(directory, "not-a-directory");
    await writeFile(blockedOutput, "occupied");
    const invalid = fixture();
    invalid.receipt.sourceRevision = "f".repeat(40);
    try {
        await assert.rejects(deployRelease({ ...invalid, config, outputDirectory: blockedOutput }), error => {
            assert.match(error.message, /source identity differs/);
            assert.match(error.message, /deployment evidence write failed/);
            assert.equal(error.deploymentEvidence.phase, "validation");
            assert.equal(error.evidenceWritten, false);
            return true;
        });
    } finally {
        await rm(directory, { recursive: true, force: true });
    }
});

test("CLI writes secret-free failure evidence and exits 2 for invalid release evidence", async () => {
    const directory = await mkdtemp(join(tmpdir(), "hexana-readiness-cli-failure-"));
    const receiptPath = join(directory, "receipt.json");
    const scanPath = join(directory, "summary.json");
    const outputDirectory = join(directory, "output");
    const invalid = fixture();
    invalid.receipt.sourceRevision = "f".repeat(40);
    await writeFile(receiptPath, JSON.stringify(invalid.receipt));
    await writeFile(scanPath, JSON.stringify(invalid.scan));
    const token = "cli-sensitive-bearer-token";
    try {
        const child = spawn(process.execPath, [
            join(import.meta.dirname, "deploy-webapp-release.mjs"),
            "--receipt", receiptPath,
            "--scan-summary", scanPath,
            "--output-dir", outputDirectory,
        ], {
            env: { ...process.env, ...validEnvironment({ HEXANA_CLOUD_CONSOLE_TOKEN: token }), TEAMCITY_BUILD_ID: "10", HEXANA_WEBAPP_BUILD_ID: "11", HEXANA_WEBAPP_SCAN_BUILD_ID: "12" },
            stdio: ["ignore", "pipe", "pipe"],
        });
        let stderr = "";
        child.stderr.setEncoding("utf8");
        child.stderr.on("data", chunk => { stderr += chunk; });
        const exitCode = await new Promise((resolveExit, rejectExit) => { child.once("error", rejectExit); child.once("close", resolveExit); });
        const raw = await readFile(join(outputDirectory, "deployment-evidence.json"), "utf8");
        const evidence = JSON.parse(raw);
        assert.equal(exitCode, 2);
        assert.equal(evidence.result, "FAILURE");
        assert.equal(evidence.phase, "validation");
        assert.deepEqual(evidence.sourceBuilds, { release: 10, build: 11, scan: 12 });
        assert.equal(raw.includes(token), false);
        assert.equal(stderr.includes(token), false);
    } finally {
        await rm(directory, { recursive: true, force: true });
    }
});

for (const [name, changed, expected, buildMayContinue] of [
    ["wrong target", { buildType: { id: "Other" } }, /target or branch/, true],
    ["wrong response revision", { revisions: { revision: [{ version: "f".repeat(40), vcsBranchName: `refs/heads/${config.branch}`, "vcs-root-instance": { id: vcsRootInstanceId } }] } }, /revision differs/, true],
    ["unspecified polled revision branch", { revisions: { revision: [{ version: revision, vcsBranchName: "<unspecified>", "vcs-root-instance": { id: vcsRootInstanceId } }] } }, /revision differs/, true],
    ["wrong polled revision branch", { revisions: { revision: [{ version: revision, vcsBranchName: "refs/heads/release/webapp/2.8", "vcs-root-instance": { id: vcsRootInstanceId } }] } }, /revision differs/, true],
    ["wrong response build ID", { id: 43 }, /identity or state/, true],
    ["failed deployment", { state: "finished", status: "FAILURE" }, /finished with FAILURE/, false],
]) test(`rejects ${name}`, async () => {
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("queued", changed)];
    const error = await capturedFailure(deployRelease({ ...fixture(), config, fetchImpl: async () => response(values.shift()) }));
    assert.match(error.message, expected);
    assert.equal(error.deploymentEvidence.failure.buildMayContinue, buildMayContinue);
});

for (const vcsBranchName of ["<unspecified>", "refs/heads/release/webapp/2.8"]) test(`rejects queued revision branch ${vcsBranchName}`, async () => {
    const queued = build("queued");
    queued.revisions.revision[0].vcsBranchName = vcsBranchName;
    const error = await capturedFailure(deployRelease({ ...fixture(), config, fetchImpl: deploymentFetch([safeBuildType(), safeVcsRoot(), rootInstances(), queued]) }));
    assert.match(error.message, /revision differs/);
    assert.equal(error.deploymentEvidence.phase, "queue");
});

for (const [name, mutateBuildType, mutateRoot, expected] of [
    ["old hyphenated profile", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replace("deploy_only_production", ["deploy", "only", "production"].join("-")); }, () => {}, /options assignment is unsafe/],
    ["other profile", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replace("deploy_only_production", "staging"); }, () => {}, /options assignment is unsafe/],
    ["multiple profiles", value => { value.steps.step[0].properties.property[0].value += " -p production"; }, () => {}, /profile is unsafe/],
    ["enabled trigger", value => { value.triggers.trigger[0].disabled = false; }, () => {}, /enabled trigger/],
    ["wrong attached root", value => { value["vcs-root-entries"]["vcs-root-entry"][0]["vcs-root"].id = "Other"; }, () => {}, /wrong VCS root/],
    ["wrong namespace", value => { buildParameter(value, "env.K8S_NAMESPACE").value = "other-namespace"; }, () => {}, /fixed deployment target/],
    ["wrong application", value => { buildParameter(value, "env.APPLICATION_NAME").value = "other-app"; }, () => {}, /fixed deployment target/],
    ["wrong cluster", value => { buildParameter(value, "env.JBCC_GCP_CLUSTER").value = "other-cluster"; }, () => {}, /fixed deployment target/],
    ["missing approved parameter", value => { value.parameters.property = value.parameters.property.filter(parameter => parameter.name !== "env.JBCC_GCP_REGION"); }, () => {}, /fixed deployment target/],
    ["duplicate approved parameter", value => { value.parameters.property.push({ ...buildParameter(value, "env.APPLICATION_NAME") }); }, () => {}, /fixed deployment target/],
    ["missing branch specification", () => {}, value => { value.properties.property = []; }, /branch specification is unsafe/],
    ["unparenthesized branch specification", () => {}, value => { value.properties.property[0].value = "+:refs/heads/release/webapp/*"; }, /branch specification is unsafe/],
    ["wrong root URL", () => {}, value => { value.properties.property.find(property => property.name === "url").value = "https://example.invalid/hexana/Other.git"; }, /root URL differs/],
    ["malformed response", value => { delete value.steps; }, () => {}, /unsupported shape/],
    ["unused Skaffold options", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replaceAll("run ${SKAFFOLD_OPTIONS}", "run"); }, () => {}, /does not run its validated options/],
    ["wrong working directory", value => { value.steps.step[0].properties.property[1].value = "."; }, () => {}, /mode or working directory/],
    ["non-custom script", value => { value.steps.step[0].properties.property[2].value = "false"; }, () => {}, /mode or working directory/],
    ["extra options assignment", value => { value.steps.step[0].properties.property[0].value += "export SKAFFOLD_OPTIONS='-p staging'\n"; }, () => {}, /options assignment is unsafe/],
    ["filename override", value => { value.steps.step[0].properties.property[0].value += "\nskaffold run -f other.yaml\n"; }, () => {}, /filename override is unsafe/],
    ["Docker build", value => { value.steps.step[0].properties.property[0].value += "\ndocker build .\n"; }, () => {}, /build or push command/],
    ["Skaffold push", value => { value.steps.step[0].properties.property[0].value += "\nskaffold push\n"; }, () => {}, /build or push command/],
    ["arbitrary command", value => { value.steps.step[0].properties.property[0].value += "\necho done\n"; }, () => {}, /normalized template/],
    ["namespace override", value => { value.steps.step[0].properties.property[0].value += "\nexport K8S_NAMESPACE=other\n"; }, () => {}, /normalized template/],
    ["image override", value => { value.steps.step[0].properties.property[0].value += "\nexport HEXANA_WEBAPP_IMAGE=mutable\n"; }, () => {}, /normalized template/],
    ["Skaffold function shadowing", value => { value.steps.step[0].properties.property[0].value = `skaffold() { true; }\n${value.steps.step[0].properties.property[0].value}`; }, () => {}, /normalized template/],
    ["Skaffold alias shadowing", value => { value.steps.step[0].properties.property[0].value = `alias skaffold=true\n${value.steps.step[0].properties.property[0].value}`; }, () => {}, /normalized template/],
    ["failure suppression", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replace("skaffold run ${SKAFFOLD_OPTIONS} 2>&1", "skaffold run ${SKAFFOLD_OPTIONS} 2>&1 || true"); }, () => {}, /normalized template/],
    ["kubectl command", value => { value.steps.step[0].properties.property[0].value += "\nkubectl delete deployment hexana-webapp\n"; }, () => {}, /normalized template/],
    ["extra Helm command", value => { value.steps.step[0].properties.property[0].value += "\nhelm uninstall hexana-webapp\n"; }, () => {}, /normalized template/],
    ["curl command", value => { value.steps.step[0].properties.property[0].value += "\ncurl https://example.invalid\n"; }, () => {}, /normalized template/],
    ["eval command", value => { value.steps.step[0].properties.property[0].value += "\neval dangerous\n"; }, () => {}, /normalized template/],
    ["source command", value => { value.steps.step[0].properties.property[0].value += "\nsource dangerous.sh\n"; }, () => {}, /normalized template/],
    ["indented kubeconfig placeholder", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replace("\n%kubeconfig%\n", "\n %kubeconfig%\n"); }, () => {}, /normalized template/],
    ["indented heredoc terminator", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replace("\nEOF\n", "\n EOF\n"); }, () => {}, /normalized template/],
    ["heredoc terminator trailing space", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replace("\nEOF\n", "\nEOF \n"); }, () => {}, /normalized template/],
    ["mismatched Skaffold executable pair", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replace("skaffold_2.10.1 run", "skaffold_2.10.2 run"); }, () => {}, /executable pair differs/],
]) test(`preflight rejects ${name} without POST`, async () => {
    const buildType = safeBuildType();
    const vcsRoot = safeVcsRoot();
    mutateBuildType(buildType);
    mutateRoot(vcsRoot);
    const calls = [];
    await assert.rejects(deployRelease({ ...fixture(), config, fetchImpl: async (url, init) => { calls.push({ url, method: init.method ?? "GET" }); return response(url.includes("/buildTypes/") ? buildType : vcsRoot); } }), expected);
    assert.equal(calls.some(call => call.method === "POST"), false);
});

test("rejects ambiguous or mismatched VCS root resolution", () => {
    assert.throws(() => resolveVcsRootInstance(rootInstances({ "vcs-root-instance": rootInstances()["vcs-root-instance"].concat(rootInstances()["vcs-root-instance"]) }), config), /exactly one/);
    assert.throws(() => resolveVcsRootInstance(rootInstances({ "vcs-root-instance": [{ id: vcsRootInstanceId, "vcs-root-id": "Other" }] }), config), /mapping differs/);
});

test("marks a thrown queue POST as ambiguous and potentially continuing", async () => {
    let postSeen = false;
    const fetchImpl = async (url, init) => {
        if (url.includes("/app/rest/buildQueue?")) { postSeen = init.method === "POST"; throw new Error("connection lost after send"); }
        if (url.includes("/buildTypes/")) return response(safeBuildType());
        if (url.includes("/vcs-roots/")) return response(safeVcsRoot());
        return response(rootInstances());
    };
    const error = await capturedFailure(deployRelease({ ...fixture(), config, fetchImpl }));
    assert.equal(postSeen, true);
    assert.equal(error.deploymentEvidence.phase, "queue");
    assert.equal(error.deploymentEvidence.cloudConsole.queueAttempted, true);
    assert.equal(error.deploymentEvidence.cloudConsole.queueAmbiguous, true);
    assert.equal(error.deploymentEvidence.cloudConsole.buildId, undefined);
    assert.equal(error.deploymentEvidence.failure.buildMayContinue, true);
});

test("marks an invalid queue response as ambiguous and potentially continuing", async () => {
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), { id: 0 }];
    const error = await capturedFailure(deployRelease({ ...fixture(), config, fetchImpl: async () => response(values.shift()) }));
    assert.match(error.message, /no positive build ID/);
    assert.equal(error.deploymentEvidence.phase, "queue");
    assert.equal(error.deploymentEvidence.cloudConsole.queueAmbiguous, true);
    assert.equal(error.deploymentEvidence.failure.buildMayContinue, true);
});

test("retains a known running build when a later poll fails", async () => {
    let clock = 0;
    let poll = 0;
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("running")];
    const fetchImpl = async url => {
        if (url.includes("/app/rest/builds/") && ++poll === 2) throw new Error("poll failed");
        return response(values.shift());
    };
    const error = await capturedFailure(deployRelease({ ...fixture(), config, fetchImpl, sleep: async ms => { clock += ms; }, now: () => clock }));
    assert.equal(error.deploymentEvidence.cloudConsole.queueAmbiguous, false);
    assert.equal(error.deploymentEvidence.cloudConsole.buildId, 42);
    assert.match(error.deploymentEvidence.cloudConsole.buildUrl, /buildId=42/);
    assert.equal(error.deploymentEvidence.cloudConsole.lastExactObservation.state, "running");
    assert.equal(error.deploymentEvidence.cloudConsole.exactBuildFinished, false);
    assert.equal(error.deploymentEvidence.failure.buildMayContinue, true);
});

test("records exact finished FAILURE as conclusive and not continuing", async () => {
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued"), build("finished", { status: "FAILURE" })];
    const error = await capturedFailure(deployRelease({ ...fixture(), config, fetchImpl: async () => response(values.shift()) }));
    assert.match(error.message, /finished with FAILURE/);
    assert.equal(error.deploymentEvidence.cloudConsole.lastExactObservation.state, "finished");
    assert.equal(error.deploymentEvidence.cloudConsole.lastExactObservation.status, "FAILURE");
    assert.equal(error.deploymentEvidence.cloudConsole.exactBuildFinished, true);
    assert.equal(error.deploymentEvidence.failure.buildMayContinue, false);
});

test("records timed-out known build as potentially continuing without cancellation", async () => {
    let clock = 0;
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), build("queued")];
    const error = await capturedFailure(deployRelease({ ...fixture(), config, fetchImpl: async () => response(values.shift() ?? build("running")), sleep: async ms => { clock += ms; }, now: () => clock }));
    assert.match(error.message, /not canceled and may continue/);
    assert.equal(error.deploymentEvidence.cloudConsole.buildId, 42);
    assert.equal(error.deploymentEvidence.cloudConsole.lastExactObservation.state, "running");
    assert.equal(error.deploymentEvidence.cloudConsole.exactBuildFinished, false);
    assert.equal(error.deploymentEvidence.failure.buildMayContinue, true);
});

test("rejects a missing bearer token before deployment", () => {
    assert.throws(() => configFromEnv({ TEAMCITY_SERVER_URL: allowedTarget.serverUrl, HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID: allowedTarget.buildTypeId, HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID: allowedTarget.vcsRootId, HEXANA_RELEASE_BRANCH: config.branch, HEXANA_RELEASE_REVISION: revision }), /HEXANA_CLOUD_CONSOLE_TOKEN/);
});

test("rejects environment attempts to redirect the pinned target", () => {
    const env = { TEAMCITY_SERVER_URL: allowedTarget.serverUrl, HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID: allowedTarget.buildTypeId, HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID: allowedTarget.vcsRootId, HEXANA_RELEASE_BRANCH: config.branch, HEXANA_RELEASE_REVISION: revision, HEXANA_CLOUD_CONSOLE_TOKEN: "secret" };
    assert.throws(() => configFromEnv({ ...env, TEAMCITY_SERVER_URL: "https://evil.example" }), /pinned TeamCity origin/);
    assert.throws(() => configFromEnv({ ...env, HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID: "Other" }), /pinned generated configuration/);
    assert.throws(() => configFromEnv({ ...env, HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID: "Other" }), /pinned generated configuration/);
});

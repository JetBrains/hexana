import test from "node:test";
import assert from "node:assert/strict";
import { allowedTarget, configFromEnv, createQueueRequest, deployMaster, masterDeploymentPolicy, masterEndpoints, validateEvidence } from "./deploy-webapp-master.mjs";
import { releaseDeploymentPolicy } from "./deploy-webapp-release.mjs";

const revision = "0123456789abcdef0123456789abcdef01234567";
const digest = `sha256:${"a".repeat(64)}`;
const instanceId = "125306";
const evidenceFiles = ["build-identity.json", "checksums.sha256", "package-manifest.json", "provenance.json", "release-info.json", "resolved-dependencies.json", "sbom.cdx.json"];
const config = { ...allowedTarget, token: "secret", branch: "master", revision, buildTimeoutMs: 1_000, readinessTimeoutMs: 1_000, pollIntervalMs: 100, sourceBuilds: { nightly: 10, build: 11, scan: 12 } };

function fixture() {
    const reference = `registry.jetbrains.team/p/hx/containers/hexana-webapp@${digest}`;
    const image = { reference, manifestDigest: digest, configDigest: `sha256:${"b".repeat(64)}`, manifestMediaType: "application/vnd.oci.image.manifest.v1+json", configMediaType: "application/vnd.oci.image.config.v1+json", platform: { os: "linux", architecture: "amd64" } };
    const identity = { schemaVersion: 1, product: "hexana-webapp", version: revision, versionSource: "git-revision", revision };
    const receipt = { schemaVersion: 2, kind: "hexana-webapp-oci-evidence", source: { branch: "master", revision }, image, package: { identity, aggregateSha256: "c".repeat(64), evidence: Object.fromEntries(evidenceFiles.map(name => [name, "d".repeat(64)])) } };
    const scan = { schemaVersion: 2, kind: "hexana-webapp-vulnerability-evidence", decision: "pass", evaluatedAt: "2026-01-01T00:00:00.000Z", source: { branch: "master", revision, version: revision }, image: { reference, manifestDigest: digest, configDigest: image.configDigest, archiveSha256: "e".repeat(64) }, sbomSha256: "f".repeat(64), scanner: {}, database: {}, reports: {}, counts: { UNKNOWN: 0, LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 }, findings: [] };
    return { receipt, scan };
}

const validEnvironment = overrides => ({
    TEAMCITY_SERVER_URL: allowedTarget.serverUrl,
    HEXANA_WEBAPP_SOURCE_BRANCH: "master",
    HEXANA_SOURCE_REVISION: revision,
    HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID: allowedTarget.buildTypeId,
    HEXANA_CLOUD_CONSOLE_VCS_ROOT_ID: allowedTarget.vcsRootId,
    HEXANA_CLOUD_CONSOLE_TOKEN: "secret",
    ...overrides,
});
const safeBuildType = () => ({
    id: config.buildTypeId,
    "vcs-root-entries": { "vcs-root-entry": [{ "vcs-root": { id: config.vcsRootId } }] },
    steps: { step: [{ type: "simpleRunner", properties: { property: [{ name: "script.content", value: masterDeploymentPolicy.approvedGeneratedScript }, { name: "teamcity.build.workingDir", value: "webapp" }, { name: "use.custom.script", value: "true" }] } }] },
    triggers: { trigger: [{ id: "TRIGGER_1", type: "vcsTrigger", disabled: true }] },
    parameters: { property: Object.entries(masterDeploymentPolicy.approvedParameters).map(([name, value]) => ({ name, value })) },
});
const safeRoot = () => ({ id: config.vcsRootId, properties: { property: [{ name: "branch", value: "refs/heads/master" }, { name: "teamcity:branchSpec", value: "+:<default>" }, { name: "url", value: "https://example.invalid/hexana/Hexana.git" }] } });
const roots = () => ({ "vcs-root-instance": [{ id: instanceId, "vcs-root-id": config.vcsRootId }] });
const build = state => ({ id: 42, state, status: state === "finished" ? "SUCCESS" : undefined, branchName: "refs/heads/master", buildType: { id: config.buildTypeId }, revisions: { revision: [{ version: revision, vcsBranchName: "refs/heads/master", "vcs-root-instance": { id: instanceId } }] } });
const response = value => ({ ok: true, status: 200, json: async () => value });
const health = url => ({ status: 204, url, headers: new Headers({ "Cache-Control": "no-store" }), body: null });
const root = (url, version = revision) => {
    const html = `<script id="hexana-release-info" type="application/json">${JSON.stringify({ schemaVersion: 1, product: "hexana-webapp", version, revision })}</script>`;
    const result = new Response(html, { status: 200 });
    Object.defineProperty(result, "url", { value: url });
    return result;
};
const deploymentFetch = (values, calls, rootVersion = revision) => async (url, init) => {
    calls.push({ url, init });
    if (new URL(url).origin === new URL(masterEndpoints[0]).origin) return new URL(url).pathname === "/healthz" ? health(url) : root(url, rootVersion);
    return response(values.shift());
};

test("validates exact SHA-only master Build and Scan evidence", () => {
    const { receipt, scan } = fixture();
    assert.deepEqual(validateEvidence(receipt, scan, "master", revision), { branch: "master", revision, version: revision, image: receipt.image.reference, digest });
    const { source: _receiptSource, ...releaseReceipt } = receipt;
    const { source: _scanSource, ...releaseScan } = scan;
    Object.assign(releaseReceipt, { schemaVersion: 1, releaseBranch: "release/webapp/0.1", sourceRevision: revision });
    Object.assign(releaseScan, { schemaVersion: 1, release: scan.source });
    const wrongIdentity = structuredClone(receipt);
    wrongIdentity.package.identity.version = "f".repeat(40);
    assert.throws(() => validateEvidence(releaseReceipt, scan, "master", revision), /unsupported shape/);
    assert.throws(() => validateEvidence(receipt, releaseScan, "master", revision), /unsupported shape/);
    assert.throws(() => validateEvidence(wrongIdentity, scan, "master", revision), /SHA-only/);
});

test("pins the master environment, target, branch, and revision", () => {
    const parsed = configFromEnv(validEnvironment({ TEAMCITY_BUILD_ID: "10", HEXANA_WEBAPP_BUILD_ID: "11", HEXANA_WEBAPP_SCAN_BUILD_ID: "12" }));
    assert.equal(parsed.branch, "master");
    assert.deepEqual(parsed.sourceBuilds, { nightly: 10, build: 11, scan: 12 });
    assert.deepEqual(masterEndpoints, ["https://hexana-webapp-master.example.invalid/"]);
    assert.ok(Object.isFrozen(masterDeploymentPolicy) && Object.isFrozen(masterDeploymentPolicy.allowedTarget));
    assert.equal(masterDeploymentPolicy.approvedGeneratedScript, releaseDeploymentPolicy.approvedGeneratedScript.replace("deploy_only_production", "deploy_only_master"));
    assert.throws(() => configFromEnv(validEnvironment({ HEXANA_WEBAPP_SOURCE_BRANCH: "release/webapp/0.1" })), /must be master/);
    assert.throws(() => configFromEnv(validEnvironment({ HEXANA_WEBAPP_SOURCE_BRANCH: " master " })), /must be master/);
    assert.throws(() => configFromEnv(validEnvironment({ HEXANA_SOURCE_REVISION: revision.toUpperCase() })), /lowercase full/);
    assert.throws(() => configFromEnv({ ...validEnvironment(), HEXANA_WEBAPP_SOURCE_BRANCH: undefined, HEXANA_RELEASE_BRANCH: "master" }), /HEXANA_WEBAPP_SOURCE_BRANCH is required/);
    assert.throws(() => configFromEnv(validEnvironment({ HEXANA_CLOUD_CONSOLE_BUILD_TYPE_ID: "Other" })), /pinned generated/);
    assert.throws(() => configFromEnv(validEnvironment({ TEAMCITY_SERVER_URL: "https://evil.example" })), /pinned TeamCity/);
});

test("queues master through TeamCity's explicit default branch and pins refs/heads/master", () => {
    const evidence = validateEvidence(...Object.values(fixture()), "master", revision);
    const request = createQueueRequest(config, evidence, instanceId);
    assert.deepEqual(JSON.parse(request.init.body), {
        buildType: { id: allowedTarget.buildTypeId },
        branchName: "<default>",
        revisions: { revision: [{ version: revision, vcsBranchName: "refs/heads/master", "vcs-root-instance": { id: instanceId } }] },
        properties: { property: [{ name: "env.HEXANA_WEBAPP_IMAGE", value: evidence.image }] },
    });
});

for (const [name, changed] of [
    ["target", { buildTypeId: "Other" }],
    ["branch", { branch: "release/webapp/0.1" }],
    ["revision", { revision: revision.toUpperCase() }],
]) test(`rejects wrong ${name} before any TeamCity request`, async () => {
    let requested = false;
    await assert.rejects(deployMaster({ ...fixture(), config: { ...config, ...changed }, fetchImpl: async () => { requested = true; } }));
    assert.equal(requested, false);
});

for (const [name, mutateBuild, mutateRoot] of [
    ["profile", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replace("deploy_only_master", "deploy_only_production"); }],
    ["application", value => { value.parameters.property.find(entry => entry.name === "env.APPLICATION_NAME").value = "hexana-webapp"; }],
    ["default branch", null, value => { value.properties.property.find(entry => entry.name === "branch").value = "refs/heads/release/webapp/0.1"; }],
    ["branch specification", null, value => { value.properties.property.find(entry => entry.name === "teamcity:branchSpec").value = "+:refs/heads/master"; }],
    ["enabled trigger", value => { value.triggers.trigger[0].disabled = false; }],
]) test(`rejects wrong ${name} before POST`, async () => {
    const buildType = safeBuildType();
    const vcsRoot = safeRoot();
    mutateBuild?.(buildType);
    mutateRoot?.(vcsRoot);
    const calls = [];
    await assert.rejects(deployMaster({ ...fixture(), config, fetchImpl: async (url, init) => { calls.push(init.method ?? "GET"); return response(url.includes("/buildTypes/") ? buildType : vcsRoot); } }));
    assert.equal(calls.includes("POST"), false);
});

test("polls the exact build and verifies master readiness", async () => {
    const calls = [];
    let clock = 0;
    const result = await deployMaster({ ...fixture(), config, fetchImpl: deploymentFetch([safeBuildType(), safeRoot(), roots(), build("queued"), build("finished")], calls), sleep: async ms => { clock += ms; }, now: () => clock });
    assert.equal(result.schemaVersion, 2);
    assert.deepEqual(result.source, { branch: "master", version: revision, revision });
    assert.equal(result.profile, "deploy_only_master");
    assert.equal(result.readiness.endpoints[0].url, masterEndpoints[0]);
    assert.equal(result.readiness.endpoints[0].ready, true);
    assert.equal(calls.filter(call => call.init.method === "POST").length, 1);
    assert.equal(JSON.parse(calls.find(call => call.init.method === "POST").init.body).branchName, "<default>");
});

test("rejects the wrong identity at the master endpoint", async () => {
    const calls = [];
    let clock = 0;
    const short = { ...config, readinessTimeoutMs: 200 };
    const error = await deployMaster({ ...fixture(), config: short, fetchImpl: deploymentFetch([safeBuildType(), safeRoot(), roots(), build("queued"), build("finished")], calls, "f".repeat(40)), sleep: async ms => { clock += ms; }, now: () => clock }).catch(value => value);
    assert.match(error.message, /approved master endpoint/);
    assert.equal(error.deploymentEvidence.phase, "master-readiness");
    assert.match(error.deploymentEvidence.readiness.endpoints[0].lastObservation.reason, /identity did not match/);
});

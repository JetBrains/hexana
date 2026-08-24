import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { allowedTarget, configFromEnv, createQueueRequest, createSafetyPreflightRequests, createVcsRootInstancesRequest, deployRelease, parseJsonStrict, resolveVcsRootInstance, validateEvidence, validateSafetyPreflight } from "./deploy-webapp-release.mjs";

const revision = "0123456789abcdef0123456789abcdef01234567";
const digest = `sha256:${"a".repeat(64)}`;
const config = { ...allowedTarget, token: "secret", branch: "release/webapp/2.7", revision, timeoutMs: 1_000, pollIntervalMs: 100, sourceBuilds: { release: 10, build: 11, scan: 12 } };
const vcsRootInstanceId = "125306";
const evidenceFiles = ["build-identity.json", "checksums.sha256", "package-manifest.json", "provenance.json", "release-info.json", "resolved-dependencies.json", "sbom.cdx.json"];
function fixture() {
    const image = `registry.jetbrains.team/p/hx/containers/hexana-webapp@${digest}`;
    const receipt = { schemaVersion: 1, kind: "hexana-webapp-oci-evidence", releaseBranch: config.branch, sourceRevision: revision, image: { reference: image, manifestDigest: digest, configDigest: `sha256:${"b".repeat(64)}`, manifestMediaType: "application/vnd.oci.image.manifest.v1+json", configMediaType: "application/vnd.oci.image.config.v1+json", platform: { os: "linux", architecture: "amd64" } }, package: { identity: { schemaVersion: 1, product: "hexana-webapp", version: "2.7.3", versionSource: "webapp/gradle.properties", revision }, aggregateSha256: "c".repeat(64), evidence: Object.fromEntries(evidenceFiles.map(name => [name, "d".repeat(64)])) } };
    const scan = { schemaVersion: 1, kind: "hexana-webapp-vulnerability-evidence", decision: "pass", evaluatedAt: "2026-01-01T00:00:00.000Z", release: { branch: config.branch, revision, version: "2.7.3" }, image: { reference: image, manifestDigest: digest, configDigest: receipt.image.configDigest, archiveSha256: "e".repeat(64) }, sbomSha256: "f".repeat(64), scanner: {}, database: {}, reports: {}, counts: { UNKNOWN: 0, LOW: 1, MEDIUM: 0, HIGH: 0, CRITICAL: 0 }, findings: [{ severity: "LOW" }] };
    return { receipt, scan };
}
const response = value => ({ ok: true, status: 200, json: async () => value });
const approvedScript = Buffer.from("IyEvYmluL2Jhc2gKc2V0IC1ldW8gcGlwZWZhaWwKZXhwb3J0IFBBVEg9JFBBVEg6L3Vzci9sb2NhbC9jdXN0b20tYmluCiAgICAgICAgICAgIAplY2hvICJDb25maWd1cmUgaGVsbSIKaGVsbSB2ZXJzaW9uCmVjaG8gJWNsb3VkLmJ1aWxkZXJzLnRva2VuJSB8IGhlbG0gcmVnaXN0cnkgbG9naW4gcmVnaXN0cnkuamV0YnJhaW5zLnRlYW0gLXUgJWNsb3VkLmJ1aWxkZXJzLmxvZ2luJSAtLXBhc3N3b3JkLXN0ZGluCiAgICAgICAgICAgIAplY2hvICJTZXR1cCBrdWJlY29uZmlnIgpleHBvcnQgSkJDQ19URU1QX0tVQkVDT05GSUc9JChta3RlbXApOyBjaG1vZCA2MDAgIiRKQkNDX1RFTVBfS1VCRUNPTkZJRyI7IHRyYXAgJ3JtIC1mICIkSkJDQ19URU1QX0tVQkVDT05GSUciJyBFWElUOwpleHBvcnQgS1VCRUNPTkZJRz0iJEpCQ0NfVEVNUF9LVUJFQ09ORklHIgpjYXQgPiAiJEtVQkVDT05GSUciIDw8J0VPRicKJWt1YmVjb25maWclCkVPRgpqYmNjLW9pZGMtY2x1c3Rlci1hdXRoCiAgICAgICAgICAgIAplY2hvICJSdW4gc2thZmZvbGQiCiAgICAgICAgICAgIApleHBvcnQgU0tBRkZPTERfT1BUSU9OUz0iLS1hc3N1bWUteWVzPXRydWUgLW4gJHtLOFNfTkFNRVNQQUNFfSAtLXN0YXR1cy1jaGVjayAtcCBkZXBsb3ktb25seS1wcm9kdWN0aW9uIC0tY2FjaGUtYXJ0aWZhY3RzPWZhbHNlIgpleHBvcnQgQVBJX1ZFUlNJT049JChjYXQgc2thZmZvbGQueWFtbCB8IGdyZXAgYXBpVmVyc2lvbiB8IHNlZCAicy9hcGlWZXJzaW9uOiBza2FmZm9sZFwvdlwoWzAtOV1cKS4qL1wxLyIpIAogICAgICAgICAgICAKaWYgWyAiJEFQSV9WRVJTSU9OIiAtZ3QgIjIiIF07IHRoZW4KICBza2FmZm9sZF8yLjEwLjEgY29uZmlnIHNldCAtLWdsb2JhbCBjb2xsZWN0LW1ldHJpY3MgZmFsc2UKICBza2FmZm9sZF8yLjEwLjEgcnVuICR7U0tBRkZPTERfT1BUSU9OU30gMj4mMQplbHNlCiAgc2thZmZvbGQgY29uZmlnIHNldCAtLWdsb2JhbCBjb2xsZWN0LW1ldHJpY3MgZmFsc2UKICBza2FmZm9sZCBydW4gJHtTS0FGRk9MRF9PUFRJT05TfSAyPiYxCmZp", "base64").toString("utf8");
assert.equal(createHash("sha256").update(approvedScript, "utf8").digest("hex"), "3ff10df8a28597618c7b83d8b235b158465a0cd262fe4c76495ae6eeefe36afd");
const safeParameters = () => [
    { name: "env.APPLICATION_NAME", value: "hexana-webapp" },
    { name: "env.K8S_NAMESPACE", value: "hexana-namespace" },
    { name: "env.IMAGE_PULL_SECRET", value: "registry-pull-secret" },
    { name: "env.JBCC_GCP_CLUSTER", value: "example-cluster" },
    { name: "env.JBCC_GCP_PROJECT", value: "example-gcp-project" },
    { name: "env.JBCC_GCP_REGION", value: "europe-west1" },
    { name: "unrelated.password.parameter" },
];
const safeBuildType = () => ({ id: config.buildTypeId, "vcs-root-entries": { "vcs-root-entry": [{ "vcs-root": { id: config.vcsRootId } }] }, steps: { step: [{ type: "simpleRunner", properties: { property: [{ name: "script.content", value: approvedScript }, { name: "teamcity.build.workingDir", value: "webapp" }, { name: "use.custom.script", value: "true" }] } }] }, triggers: { trigger: [{ id: "TRIGGER_1", type: "vcsTrigger", disabled: true }] }, parameters: { property: safeParameters() } });
const safeVcsRoot = () => ({ id: config.vcsRootId, properties: { property: [{ name: "teamcity:branchSpec", value: "+:refs/heads/release/webapp/*" }, { name: "url", value: "https://example.invalid/hexana/Hexana.git" }] } });
const buildParameter = (value, name) => value.parameters.property.find(parameter => parameter.name === name);
const rootInstances = (overrides = {}) => ({ "vcs-root-instance": [{ id: vcsRootInstanceId, "vcs-root-id": config.vcsRootId }], ...overrides });
const build = (state, overrides = {}) => ({ id: 42, state, status: state === "finished" ? "SUCCESS" : undefined, branchName: `refs/heads/${config.branch}`, buildType: { id: config.buildTypeId }, revisions: { revision: [{ version: revision, "vcs-root-instance": { id: vcsRootInstanceId } }] }, ...overrides });

test("validates same-series immutable Build and passing Scan evidence", () => {
    const { receipt, scan } = fixture();
    assert.deepEqual(validateEvidence(receipt, scan, config.branch, revision), { branch: config.branch, revision, version: "2.7.3", image: receipt.image.reference, digest });
    assert.throws(() => validateEvidence(receipt, scan, "main", revision), /selected branch/);
    assert.throws(() => parseJsonStrict('{"a":1,"a":2}', "fixture"), /duplicate key/);
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
    assert.equal(request.url, `${allowedTarget.serverUrl}/app/rest/buildQueue`);
    assert.deepEqual(JSON.parse(request.init.body).properties.property, [{ name: "env.HEXANA_WEBAPP_IMAGE", value: validated.image }]);
    assert.equal(JSON.parse(request.init.body).revisions.revision[0].version, revision);
    assert.deepEqual(JSON.parse(request.init.body).revisions.revision[0]["vcs-root-instance"], { id: vcsRootInstanceId });
    assert.equal(request.init.headers.Authorization, "Bearer secret");
});

test("uses explicit fields and accepts only the safe generated configuration", () => {
    const requests = createSafetyPreflightRequests(config);
    assert.match(requests[0].url, /fields=id,vcs-root-entries\(vcs-root-entry\(vcs-root\(id\)\)\),steps\(step\(type,properties\(property\(name,value\)\)\)\),triggers\(trigger\(id,type,disabled\)\),parameters\(property\(name,value\)\)$/);
    assert.match(requests[1].url, /fields=id,properties\(property\(name,value\)\)$/);
    assert.doesNotThrow(() => validateSafetyPreflight(safeBuildType(), safeVcsRoot(), config));
});

test("queues, polls, and returns secret-free deployment evidence", async () => {
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), { id: 42 }, build("running"), build("finished")];
    const urls = [];
    let clock = 0;
    const redirects = [];
    const result = await deployRelease({ ...fixture(), config, fetchImpl: async (url, init) => { urls.push(url); redirects.push(init.redirect); return response(values.shift()); }, sleep: async ms => { clock += ms; }, now: () => clock });
    assert.equal(result.cloudConsole.buildId, 42);
    assert.equal(result.cloudConsole.vcsRootInstanceId, vcsRootInstanceId);
    assert.match(urls.at(-1), /fields=id,state,status,branchName,buildType\(id\),revisions\(revision\(version,vcs-root-instance\(id\)\)\)$/);
    assert.ok(redirects.every(value => value === "error"));
    assert.deepEqual(result.sourceBuilds, config.sourceBuilds);
    assert.equal(JSON.stringify(result).includes(config.token), false);
});

for (const [name, changed, expected] of [
    ["wrong target", { buildType: { id: "Other" } }, /target or branch/],
    ["wrong response revision", { revisions: { revision: [{ version: "f".repeat(40), "vcs-root-instance": { id: vcsRootInstanceId } }] } }, /revision differs/],
    ["wrong response build ID", { id: 43 }, /identity or state/],
    ["failed deployment", { state: "finished", status: "FAILURE" }, /finished with FAILURE/],
]) test(`rejects ${name}`, async () => { const values = [safeBuildType(), safeVcsRoot(), rootInstances(), { id: 42 }, build("queued", changed)]; await assert.rejects(deployRelease({ ...fixture(), config, fetchImpl: async () => response(values.shift()) }), expected); });

for (const [name, mutateBuildType, mutateRoot, expected] of [
    ["wrong profile", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replace("deploy-only-production", "staging"); }, () => {}, /options assignment is unsafe/],
    ["multiple profiles", value => { value.steps.step[0].properties.property[0].value += " -p production"; }, () => {}, /profile is unsafe/],
    ["enabled trigger", value => { value.triggers.trigger[0].disabled = false; }, () => {}, /enabled trigger/],
    ["wrong attached root", value => { value["vcs-root-entries"]["vcs-root-entry"][0]["vcs-root"].id = "Other"; }, () => {}, /wrong VCS root/],
    ["wrong namespace", value => { buildParameter(value, "env.K8S_NAMESPACE").value = "other-namespace"; }, () => {}, /fixed deployment target/],
    ["wrong application", value => { buildParameter(value, "env.APPLICATION_NAME").value = "other-app"; }, () => {}, /fixed deployment target/],
    ["wrong cluster", value => { buildParameter(value, "env.JBCC_GCP_CLUSTER").value = "other-cluster"; }, () => {}, /fixed deployment target/],
    ["missing approved parameter", value => { value.parameters.property = value.parameters.property.filter(parameter => parameter.name !== "env.JBCC_GCP_REGION"); }, () => {}, /fixed deployment target/],
    ["duplicate approved parameter", value => { value.parameters.property.push({ ...buildParameter(value, "env.APPLICATION_NAME") }); }, () => {}, /fixed deployment target/],
    ["missing branch specification", () => {}, value => { value.properties.property = []; }, /branch specification is unsafe/],
    ["wrong root URL", () => {}, value => { value.properties.property.find(property => property.name === "url").value = "https://example.invalid/hexana/Other.git"; }, /root URL differs/],
    ["malformed response", value => { delete value.steps; }, () => {}, /unsupported shape/],
    ["unused Skaffold options", value => { value.steps.step[0].properties.property[0].value = value.steps.step[0].properties.property[0].value.replaceAll("run ${SKAFFOLD_OPTIONS}", "run"); }, () => {}, /does not run its validated options/],
    ["wrong working directory", value => { value.steps.step[0].properties.property[1].value = "."; }, () => {}, /mode or working directory/],
    ["non-custom script", value => { value.steps.step[0].properties.property[2].value = "false"; }, () => {}, /mode or working directory/],
    ["extra options assignment", value => { value.steps.step[0].properties.property[0].value += "export SKAFFOLD_OPTIONS='-p staging'\n"; }, () => {}, /options assignment is unsafe/],
    ["filename override", value => { value.steps.step[0].properties.property[0].value += "\nskaffold run -f other.yaml\n"; }, () => {}, /filename override is unsafe/],
    ["Docker build", value => { value.steps.step[0].properties.property[0].value += "\ndocker build .\n"; }, () => {}, /build or push command/],
    ["Skaffold push", value => { value.steps.step[0].properties.property[0].value += "\nskaffold push\n"; }, () => {}, /build or push command/],
    ["harmless extra command", value => { value.steps.step[0].properties.property[0].value += "\necho done\n"; }, () => {}, /script fingerprint differs/],
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

test("rejects a queue response without a positive build ID", async () => {
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), { id: 0 }];
    await assert.rejects(deployRelease({ ...fixture(), config, fetchImpl: async () => response(values.shift()) }), /no positive build ID/);
});

test("rejects deployment timeout", async () => {
    let clock = 0;
    const values = [safeBuildType(), safeVcsRoot(), rootInstances(), { id: 42 }];
    await assert.rejects(deployRelease({ ...fixture(), config, fetchImpl: async () => response(values.shift() ?? build("running")), sleep: async ms => { clock += ms; }, now: () => clock }), /timed out/);
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

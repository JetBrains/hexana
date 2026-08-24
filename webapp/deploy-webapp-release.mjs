import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repository = "registry.jetbrains.team/p/hx/containers/hexana-webapp";
const branchPattern = /^release\/webapp\/(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const revisionPattern = /^[0-9a-f]{40}$/;
const digestPattern = /^sha256:[0-9a-f]{64}$/;
const versionPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const evidenceFiles = ["build-identity.json", "checksums.sha256", "package-manifest.json", "provenance.json", "release-info.json", "resolved-dependencies.json", "sbom.cdx.json"];
const approvedScriptSha256 = "3ff10df8a28597618c7b83d8b235b158465a0cd262fe4c76495ae6eeefe36afd";
const approvedVcsUrl = "https://example.invalid/hexana/Hexana.git";
const approvedParameters = Object.freeze({
    "env.APPLICATION_NAME": "hexana-webapp",
    "env.K8S_NAMESPACE": "hexana-namespace",
    "env.IMAGE_PULL_SECRET": "registry-pull-secret",
    "env.JBCC_GCP_CLUSTER": "example-cluster",
    "env.JBCC_GCP_PROJECT": "example-gcp-project",
    "env.JBCC_GCP_REGION": "europe-west1",
});
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
    const timeoutMs = boundedInteger(env.HEXANA_DEPLOY_TIMEOUT_MS, 1_800_000, 1_000, 7_200_000, "HEXANA_DEPLOY_TIMEOUT_MS");
    const pollIntervalMs = boundedInteger(env.HEXANA_DEPLOY_POLL_INTERVAL_MS, 5_000, 100, 60_000, "HEXANA_DEPLOY_POLL_INTERVAL_MS");
    if (pollIntervalMs >= timeoutMs) fail("poll interval must be shorter than timeout");
    const token = required(env, "HEXANA_CLOUD_CONSOLE_TOKEN");
    if (/[\x00-\x20]/.test(token)) fail("HEXANA_CLOUD_CONSOLE_TOKEN is malformed");
    return { serverUrl: allowedTarget.serverUrl, token, buildTypeId, vcsRootId, branch: required(env, "HEXANA_RELEASE_BRANCH"), revision: required(env, "HEXANA_RELEASE_REVISION"), timeoutMs, pollIntervalMs, sourceBuilds: { release: optionalBuildId(env, "TEAMCITY_BUILD_ID"), build: optionalBuildId(env, "HEXANA_WEBAPP_BUILD_ID"), scan: optionalBuildId(env, "HEXANA_WEBAPP_SCAN_BUILD_ID") } };
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
    const expectedAssignment = 'export SKAFFOLD_OPTIONS="--assume-yes=true -n ${K8S_NAMESPACE} --status-check -p deploy-only-production --cache-artifacts=false"';
    const assignments = scripts[0].value.split(/\r?\n/).filter(line => line.includes("SKAFFOLD_OPTIONS="));
    if (assignments.length !== 1 || assignments[0] !== expectedAssignment) fail("Cloud Console Skaffold options assignment is unsafe");
    if (!/\bskaffold(?:_\d+(?:\.\d+)*)?\s+run\s+\$\{SKAFFOLD_OPTIONS\}(?:\s|$)/.test(scripts[0].value)) fail("Cloud Console Skaffold script does not run its validated options");
    const profiles = [...scripts[0].value.matchAll(/(?:^|\s)(?:-p|--profile)(?:=|\s+)([^\s"'\\]+)/g)].map(match => match[1]);
    if (profiles.length !== 1 || profiles[0] !== "deploy-only-production") fail("Cloud Console Skaffold profile is unsafe");
    if (/\bskaffold(?:_\d+(?:\.\d+)*)?\b[^\r\n;&|]*\s(?:-f|--filename)(?:=|\s)/m.test(scripts[0].value)) fail("Cloud Console Skaffold filename override is unsafe");
    if (/\bdocker\b[^\r\n;&|]*(?:\bbuild(?:x)?\b|\bpush\b)|\bskaffold(?:_\d+(?:\.\d+)*)?\b[^\r\n;&|]*(?:\bbuild\b|\bpush\b)/i.test(scripts[0].value)) fail("Cloud Console script contains a build or push command");
    if (createHash("sha256").update(scripts[0].value, "utf8").digest("hex") !== approvedScriptSha256) fail("Cloud Console Skaffold script fingerprint differs from the approved generated script");

    exactKeys(buildType.triggers, ["trigger"], "Cloud Console triggers");
    const triggers = requireArray(buildType.triggers.trigger, "Cloud Console triggers");
    triggers.forEach((trigger, index) => { exactKeys(trigger, ["id", "type", "disabled"], `Cloud Console trigger ${index}`); if (trigger.disabled !== true) fail("Cloud Console build type has an enabled trigger"); });

    exactKeys(vcsRoot, ["id", "properties"], "Cloud Console VCS root");
    if (vcsRoot.id !== config.vcsRootId) fail("Cloud Console VCS root ID differs");
    const vcsRootProperties = properties(vcsRoot.properties, "Cloud Console VCS root properties");
    const branchSpecs = vcsRootProperties.filter(property => property.name === "teamcity:branchSpec");
    if (branchSpecs.length !== 1 || branchSpecs[0].value !== "+:refs/heads/release/webapp/*") fail("Cloud Console VCS root branch specification is unsafe");
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
    if (value.state === "finished" && value.status !== "SUCCESS") fail(`Cloud Console deployment finished with ${value.status ?? "unknown status"}`);
    return value.state === "finished";
}

async function requestJson(fetchImpl, url, init, timeoutMs) {
    let response;
    try { response = await fetchImpl(url, { ...init, redirect: "error", signal: AbortSignal.timeout(Math.max(1, Math.min(30_000, timeoutMs))) }); } catch { fail("TeamCity request failed"); }
    if (!response?.ok) fail(`TeamCity returned HTTP ${response?.status ?? "unknown"}`);
    try { return await response.json(); } catch { fail("TeamCity response is not JSON"); }
}

const buildStatusUrl = (config, buildId) => `${config.serverUrl}/app/rest/builds/id:${buildId}?fields=id,state,status,branchName,buildType(id),revisions(revision(version,vcs-root-instance(id)))`;

export async function deployRelease({ receipt, scan, config, fetchImpl = fetch, sleep = ms => new Promise(done => setTimeout(done, ms)), now = Date.now }) {
    validatePinnedTarget(config);
    const evidence = validateEvidence(receipt, scan, config.branch, config.revision);
    const deadline = now() + config.timeoutMs;
    const preflightRequests = createSafetyPreflightRequests(config);
    const [buildType, vcsRoot] = await Promise.all(preflightRequests.map(request => requestJson(fetchImpl, request.url, request.init, deadline - now())));
    validateSafetyPreflight(buildType, vcsRoot, config);
    const rootRequest = createVcsRootInstancesRequest(config);
    const vcsRootInstanceId = resolveVcsRootInstance(await requestJson(fetchImpl, rootRequest.url, rootRequest.init, deadline - now()), config);
    const queued = createQueueRequest(config, evidence, vcsRootInstanceId);
    const queueResponse = await requestJson(fetchImpl, queued.url, queued.init, deadline - now());
    const buildId = queueResponse?.id;
    if (!Number.isInteger(buildId) || buildId <= 0) fail("Cloud Console queue response has no positive build ID");
    if (deadline - now() <= 0) fail("Cloud Console deployment timed out");
    let build = await requestJson(fetchImpl, buildStatusUrl(config, buildId), { headers: { Accept: "application/json", Authorization: `Bearer ${config.token}` } }, deadline - now());
    while (!validateBuild(build, config, evidence, vcsRootInstanceId, buildId)) {
        const remaining = deadline - now();
        if (remaining <= 0) fail("Cloud Console deployment timed out");
        await sleep(Math.min(config.pollIntervalMs, remaining));
        if (deadline - now() <= 0) fail("Cloud Console deployment timed out");
        build = await requestJson(fetchImpl, buildStatusUrl(config, buildId), { headers: { Accept: "application/json", Authorization: `Bearer ${config.token}` } }, deadline - now());
    }
    return { schemaVersion: 1, kind: "hexana-webapp-deployment-evidence", result: "SUCCESS", profile: "deploy-only-production", release: { branch: evidence.branch, version: evidence.version, revision: evidence.revision }, image: { reference: evidence.image, manifestDigest: evidence.digest }, sourceBuilds: Object.fromEntries(Object.entries(config.sourceBuilds).filter(([, value]) => value !== undefined)), cloudConsole: { buildTypeId: config.buildTypeId, vcsRootId: config.vcsRootId, vcsRootInstanceId, buildId, buildUrl: `${config.serverUrl}/viewLog.html?buildId=${buildId}` } };
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
    const output = resolve(outputDirectory, "deployment-evidence.json");
    await mkdir(outputDirectory, { recursive: true });
    await rm(output, { force: true });
    const [receipt, scan] = await Promise.all([readFile(input.get("--receipt"), "utf8").then(value => parseJsonStrict(value, "Build receipt")), readFile(input.get("--scan-summary"), "utf8").then(value => parseJsonStrict(value, "Scan summary"))]);
    const evidence = await deployRelease({ receipt, scan, config: configFromEnv(process.env) });
    const temporary = resolve(output + `.${randomUUID()}.tmp`);
    try { await writeFile(temporary, `${JSON.stringify(evidence)}\n`, { flag: "wx" }); await rename(temporary, output); } finally { await rm(temporary, { force: true }); }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error(error.message); process.exitCode = 2; });

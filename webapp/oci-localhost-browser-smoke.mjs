import { createHash } from "node:crypto";
import { lstat, mkdir, readFile, writeFile } from "node:fs/promises";
import { basename, join, relative, resolve } from "node:path";
import { BrowserSmokeFailure, runCdpBrowser } from "./live-webapp-smoke.mjs";

const repository = "registry.jetbrains.team/p/hx/containers/hexana-webapp";
const digestPattern = /^sha256:[0-9a-f]{64}$/;
const revisionPattern = /^[0-9a-f]{40}$/;
const versionPattern = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const outputRoot = resolve(new URL("./build/oci-release/", import.meta.url).pathname);
const requiredOptions = ["--url", "--image-reference", "--expected-digest", "--expected-version", "--expected-revision", "--wasm-fixture", "--output-dir"];

export class LocalOciBrowserSmokeFailure extends Error {
    constructor(phase, message, cause) {
        super(`${phase}: ${message}`, { cause });
        this.name = "LocalOciBrowserSmokeFailure";
        this.phase = phase;
    }
}

function fail(phase, message, cause) { throw new LocalOciBrowserSmokeFailure(phase, message, cause); }
function xml(value) { return String(value).replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;" })[character]); }

function readOptions(argumentsList) {
    if (argumentsList.length !== requiredOptions.length * 2) fail("input", `expected exactly ${requiredOptions.join(", ")}`);
    const values = new Map();
    for (let index = 0; index < argumentsList.length; index += 2) {
        const [name, value] = [argumentsList[index], argumentsList[index + 1]];
        if (!requiredOptions.includes(name) || !value || values.has(name)) fail("input", "options must be unique explicit name/value pairs");
        values.set(name, value);
    }
    if (values.size !== requiredOptions.length) fail("input", `expected exactly ${requiredOptions.join(", ")}`);
    return Object.fromEntries(requiredOptions.map(name => [name.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase()), values.get(name)]));
}

async function validateInput(argumentsList) {
    const values = readOptions(argumentsList);
    const urlMatch = /^http:\/\/127\.0\.0\.1:([1-9]\d{0,4})\/$/.exec(values.url);
    if (!urlMatch || Number(urlMatch[1]) > 65535) fail("input", "--url must be an exact http://127.0.0.1:<port>/ root URL");
    if (!versionPattern.test(values.expectedVersion)) fail("input", "--expected-version must be strict semantic versioning");
    if (!revisionPattern.test(values.expectedRevision)) fail("input", "--expected-revision must be a lowercase 40-character SHA");
    if (!digestPattern.test(values.expectedDigest)) fail("input", "--expected-digest must be a canonical lowercase sha256 digest");
    if (!new RegExp(`^${repository.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}@sha256:[0-9a-f]{64}$`).test(values.imageReference)) fail("input", "--image-reference must be the canonical Hexana webapp digest reference");
    if (values.imageReference !== `${repository}@${values.expectedDigest}`) fail("input", "--image-reference digest must equal --expected-digest");
    const outputDir = resolve(values.outputDir);
    const outputRelative = relative(outputRoot, outputDir);
    if (!outputRelative || outputRelative === ".." || outputRelative.startsWith(`..${process.platform === "win32" ? "\\" : "/"}`) || outputRelative === ".") fail("input", `--output-dir must be below ${outputRoot}`);
    const fixture = resolve(values.wasmFixture);
    let fixtureStat;
    try { fixtureStat = await lstat(fixture); }
    catch (error) { fail("input", "--wasm-fixture must name an existing immutable addition.wasm fixture", error); }
    if (!fixtureStat.isFile() || fixtureStat.isSymbolicLink() || basename(fixture) !== "addition.wasm") fail("input", "--wasm-fixture must name an existing immutable addition.wasm fixture");
    const wasmBytes = await readFile(fixture);
    if (!wasmBytes.length) fail("input", "--wasm-fixture must not be empty");
    return { ...values, outputDir, fixture, wasmBytes, fixtureSha256: createHash("sha256").update(wasmBytes).digest("hex") };
}

async function writeJson(file, value) { await writeFile(file, JSON.stringify(value, null, 2) + "\n"); }

export async function runLocalOciBrowserSmoke(argumentsList = process.argv.slice(2), options = {}) {
    const startedAt = new Date().toISOString();
    let input, browser, failure;
    let outputDir;
    try {
        input = await validateInput(argumentsList);
        outputDir = input.outputDir;
        await mkdir(outputDir, { recursive: true });
        browser = await (options.browserRunner || runCdpBrowser)({
            endpoint: { name: "localhost", secureUrl: input.url, httpUrl: input.url },
            input: { version: input.expectedVersion, revision: input.expectedRevision },
            wasmBytes: input.wasmBytes,
        });
        if (!browser?.screenshot?.length) fail("browser/operational", "browser runner did not return a screenshot");
        await writeJson(join(outputDir, "browser-log.json"), browser.browserLog || []);
        await writeFile(join(outputDir, "screenshot.png"), browser.screenshot);
    } catch (error) {
        failure = error instanceof LocalOciBrowserSmokeFailure
            ? error
            : new LocalOciBrowserSmokeFailure(error instanceof BrowserSmokeFailure ? `browser/${error.phase}` : "browser/operational", error.message, error);
        if (outputDir) {
            const log = error.browserLog || browser?.browserLog || [];
            await writeJson(join(outputDir, "browser-log.json"), log);
            if (error.screenshot) await writeFile(join(outputDir, "screenshot.png"), error.screenshot);
        }
    }
    if (!outputDir) throw failure;
    const summary = {
        schemaVersion: 1,
        kind: "hexana-webapp-local-oci-browser-smoke",
        status: failure ? "failed" : "passed",
        rootUrl: input.url,
        imageReference: input.imageReference,
        expectedDigest: input.expectedDigest,
        expectedVersion: input.expectedVersion,
        expectedRevision: input.expectedRevision,
        fixture: { name: basename(input.fixture), sha256: input.fixtureSha256 },
        digestVerification: {
            status: "input-correlated",
            verifiedBy: "image-reference digest equals expected-digest CLI input",
            verificationScope: "image and runtime verification are owned by the caller",
            inputRelationshipPassed: true,
            consoleDeploymentProof: false,
        },
        startedAt,
        finishedAt: new Date().toISOString(),
        failure: failure ? { phase: failure.phase, message: failure.message } : null,
    };
    await writeJson(join(outputDir, "summary.json"), summary);
    const failureXml = failure ? `<failure type="${xml(failure.phase)}" message="${xml(failure.message)}">${xml(failure.stack || failure.message)}</failure>` : "";
    await writeFile(join(outputDir, "junit.xml"), `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="Hexana local OCI browser smoke" tests="1" failures="${failure ? 1 : 0}"><testcase name="localhost-oci">${failureXml}</testcase></testsuite>\n`);
    if (failure) throw failure;
    return summary;
}

if (process.argv[1] === new URL(import.meta.url).pathname) await runLocalOciBrowserSmoke();

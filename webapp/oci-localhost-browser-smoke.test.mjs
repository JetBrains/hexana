import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { join, resolve } from "node:path";
import test from "node:test";
import { BrowserSmokeFailure } from "./live-webapp-smoke.mjs";
import { runLocalOciBrowserSmoke, LocalOciBrowserSmokeFailure } from "./oci-localhost-browser-smoke.mjs";
import { ParserWorkerSmokeFailure, runParserWorkerFlow } from "./production-package-smoke.mjs";

const revision = "0123456789abcdef0123456789abcdef01234567";
const digest = `sha256:${"a".repeat(64)}`;
const fixture = resolve(new URL("../wasmParser/src/commonTest/resources/snippets/addition.wasm", import.meta.url).pathname);
const outputRoot = resolve(new URL("./build/oci-release/", import.meta.url).pathname);

function argumentsFor(outputDir, overrides = {}) {
    const values = {
        url: "http://127.0.0.1:4173/",
        imageReference: `registry.jetbrains.team/p/hx/containers/hexana-webapp@${digest}`,
        expectedDigest: digest,
        expectedVersion: "1.2.3",
        expectedRevision: revision,
        wasmFixture: fixture,
        outputDir,
        ...overrides,
    };
    return Object.entries(values).flatMap(([name, value]) => [`--${name.replace(/[A-Z]/g, letter => `-${letter.toLowerCase()}`)}`, value]);
}

async function workerEvaluate(state) {
    return expression => {
        if (expression.includes("Boolean(document.querySelector")) return true;
        if (expression.includes("target.dispatchEvent")) return true;
        if (expression.includes("data-parser-worker-state")) return state;
        return "[]";
    };
}

test("localhost OCI browser smoke rejects preflight inputs without launching a browser", async () => {
    await mkdir(outputRoot, { recursive: true });
    const directory = await mkdtemp(join(outputRoot, "oci-local-preflight-"));
    let launches = 0;
    const run = args => runLocalOciBrowserSmoke(args, { browserRunner: async () => { launches++; } });
    try {
        for (const overrides of [
            { url: "http://localhost:4173/" },
            { imageReference: "registry.jetbrains.team/p/hx/containers/hexana-webapp:latest" },
            { expectedDigest: `sha256:${"b".repeat(64)}` },
            { expectedVersion: "01.2.3" },
            { expectedRevision: "A".repeat(40) },
        ]) await assert.rejects(run(argumentsFor(join(directory, String(launches)), overrides)), error => error instanceof LocalOciBrowserSmokeFailure && error.phase === "input");
        assert.equal(launches, 0);
    } finally { await rm(directory, { recursive: true, force: true }); }
});

test("localhost OCI browser smoke keeps About and parser-worker failures distinct", async () => {
    await mkdir(outputRoot, { recursive: true });
    const directory = await mkdtemp(join(outputRoot, "oci-local-failures-"));
    const verify = async (name, phase) => assert.rejects(
        runLocalOciBrowserSmoke(argumentsFor(join(directory, name)), { browserRunner: async () => { throw new BrowserSmokeFailure(phase, phase); } }),
        error => error instanceof LocalOciBrowserSmokeFailure && error.phase === `browser/${phase}`,
    );
    try {
        await verify("identity", "identity");
        await verify("worker-failed", "parser-worker/failed");
        await verify("worker-timed-out", "parser-worker/timed_out");
    } finally { await rm(directory, { recursive: true, force: true }); }
});

test("parser worker flow classifies failed and timed-out states", async () => {
    await assert.rejects(runParserWorkerFlow(await workerEvaluate({ state: "failed" }), Buffer.of(0), { attempts: 1, delayImpl: async () => {} }), error => error instanceof ParserWorkerSmokeFailure && error.reason === "failed");
    await assert.rejects(runParserWorkerFlow(await workerEvaluate({ state: "timed_out" }), Buffer.of(0), { attempts: 1, delayImpl: async () => {} }), error => error instanceof ParserWorkerSmokeFailure && error.reason === "timed_out");
});

test("localhost OCI browser smoke writes complete local-only evidence", async () => {
    await mkdir(outputRoot, { recursive: true });
    const directory = await mkdtemp(join(outputRoot, "oci-local-pass-"));
    const outputDir = join(directory, "evidence");
    try {
        const summary = await runLocalOciBrowserSmoke(argumentsFor(outputDir), { browserRunner: async ({ endpoint, input, wasmBytes }) => {
            assert.equal(endpoint.secureUrl, "http://127.0.0.1:4173/");
            assert.deepEqual(input, { version: "1.2.3", revision });
            assert.ok(wasmBytes.length > 0);
            return { browserLog: [{ telemetry: "disabled", externalRequests: [] }], screenshot: Buffer.from("png fixture") };
        } });
        assert.equal(summary.status, "passed");
        assert.equal(summary.expectedDigest, digest);
        assert.equal(summary.digestVerification.status, "input-correlated");
        assert.match(summary.digestVerification.verificationScope, /owned by the caller/);
        assert.equal(summary.digestVerification.consoleDeploymentProof, false);
        for (const name of ["summary.json", "junit.xml", "browser-log.json", "screenshot.png"]) await stat(join(outputDir, name));
        assert.match(await readFile(join(outputDir, "junit.xml"), "utf8"), /failures="0"/);
    } finally { await rm(directory, { recursive: true, force: true }); }
});

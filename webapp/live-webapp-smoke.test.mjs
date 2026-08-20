import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { runLiveSmoke, SmokeFailure } from "./live-webapp-smoke.mjs";

const goodRevision = "0123456789abcdef0123456789abcdef01234567";
const otherRevision = "89abcdef0123456789abcdef0123456789abcdef";
const digest = `sha256:${"a".repeat(64)}`;
const securityHeaders = {
    "Content-Security-Policy": "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://eu.i.posthog.com; img-src 'self' data:; worker-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Permissions-Policy": "geolocation=(), microphone=(), camera=(), payment=(), usb=()",
    "X-Frame-Options": "DENY",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Embedder-Policy": "require-corp",
    "Cross-Origin-Resource-Policy": "same-origin",
};

const hashName = (name, bytes) => {
    const dot = name.lastIndexOf(".");
    return `${name.slice(0, dot)}.${createHash("sha256").update(bytes).digest("hex").slice(0, 16)}${name.slice(dot)}`;
};

async function fixture() {
    const workerBody = Buffer.from("worker fixture");
    const workerName = hashName("parser-worker.js", workerBody);
    const appBody = Buffer.from(`const worker = "assets/${workerName}";`);
    const appName = hashName("webapp.js", appBody);
    const cssBody = Buffer.from("body { color: black; }");
    const cssName = hashName("theme.css", cssBody);
    const assets = new Map([[workerName, workerBody], [appName, appBody], [cssName, cssBody]]);
    const state = { revision: goodRevision, missingHeader: undefined, requestCount: 0 };
    let origin;
    const server = createServer((request, response) => {
        const path = new URL(request.url, origin).pathname;
        const redirect = /^\/redirect\/(private|public)\/$/.exec(path);
        if (redirect) return response.writeHead(308, { Location: `${origin}/${redirect[1]}/` }).end();
        const route = /^\/(private|public)\/(.*)$/.exec(path);
        if (!route) return response.writeHead(404).end();
        const relative = route[2];
        const headers = { ...securityHeaders };
        if (state.missingHeader) delete headers[state.missingHeader];
        if (relative === "healthz") return response.writeHead(204, { ...headers, "Cache-Control": "no-store" }).end();
        if (relative === "robots.txt") return response.writeHead(200, { ...headers, "Cache-Control": "no-store" }).end("User-agent: *\nDisallow: /\n");
        const asset = /^assets\/(.+)$/.exec(relative);
        if (asset && assets.has(asset[1])) return response.writeHead(200, { ...headers, "Cache-Control": "public, max-age=31536000, immutable" }).end(assets.get(asset[1]));
        if (relative === "" || relative === "index.html") {
            const info = { version: "1.2.3", revision: state.revision, repositoryUrl: "https://github.com/JetBrains/hexana", websiteUrl: "https://jetbrains.github.io/hexana/" };
            const html = `<html><head><link href="assets/${cssName}" rel="stylesheet"><script id="hexana-release-info" type="application/json">${JSON.stringify(info)}</script></head><body><script src="assets/${appName}"></script></body></html>`;
            return response.writeHead(200, { ...headers, "Cache-Control": relative ? "no-cache" : "no-store" }).end(html);
        }
        response.writeHead(404).end();
    });
    await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
    origin = `http://127.0.0.1:${server.address().port}`;
    return {
        state,
        endpoints: ["private", "public"].map(name => ({ name, secureUrl: `${origin}/${name}/`, httpUrl: `${origin}/redirect/${name}/` })),
        fetchImpl: (...argumentsList) => { state.requestCount++; return fetch(...argumentsList); },
        close: () => new Promise(resolve => server.close(resolve)),
    };
}

const environment = revision => ({
    HEXANA_WEBAPP_SMOKE_TARGET: "production",
    HEXANA_WEBAPP_EXPECTED_VERSION: "1.2.3",
    HEXANA_WEBAPP_EXPECTED_REVISION: revision,
    HEXANA_WEBAPP_EXPECTED_DIGEST: digest,
});

test("live smoke has source-owned breaking proofs and passes a clean two-host fixture", async t => {
    const site = await fixture();
    const temporary = await mkdtemp(join(tmpdir(), "hexana-live-smoke-test-"));
    const browserRunner = async ({ input, wasmBytes }) => {
        assert.equal(input.revision, goodRevision);
        assert.ok(wasmBytes.length > 0);
        return { browserLog: [{ telemetryEnabled: false, externalRequests: [] }], screenshot: Buffer.from("png fixture") };
    };
    try {
        await t.test("rejects an unapproved target before any request", async () => {
            const before = site.state.requestCount;
            await assert.rejects(runLiveSmoke({ ...environment(goodRevision), HEXANA_WEBAPP_SMOKE_TARGET: "preview" }, { outputDir: join(temporary, "invalid"), endpoints: site.endpoints, fetchImpl: site.fetchImpl, browserRunner }), error => error instanceof SmokeFailure && error.phase === "input");
            assert.equal(site.state.requestCount, before);
        });
        await t.test("rejects a target-specific version contract before any request", async () => {
            const before = site.state.requestCount;
            await assert.rejects(runLiveSmoke({ ...environment(goodRevision), HEXANA_WEBAPP_SMOKE_TARGET: "master", HEXANA_WEBAPP_EXPECTED_VERSION: "1.2.3" }, { outputDir: join(temporary, "master-version"), endpoints: [site.endpoints[0]], fetchImpl: site.fetchImpl, browserRunner }), error => error.phase === "input");
            assert.equal(site.state.requestCount, before);
        });
        await t.test("reports a wrong revision as HTTP identity failure", async () => {
            await assert.rejects(runLiveSmoke(environment(otherRevision), { outputDir: join(temporary, "revision"), endpoints: site.endpoints, fetchImpl: site.fetchImpl, browserRunner }), error => error.phase === "http/identity");
        });
        await t.test("reports a missing required header as HTTP header failure", async () => {
            site.state.missingHeader = "X-Frame-Options";
            await assert.rejects(runLiveSmoke(environment(goodRevision), { outputDir: join(temporary, "header"), endpoints: site.endpoints, fetchImpl: site.fetchImpl, browserRunner }), error => error.phase === "http/header");
            site.state.missingHeader = undefined;
        });
        await t.test("passes after restoring the fixture and writes complete evidence", async () => {
            const outputDir = join(temporary, "pass");
            const summary = await runLiveSmoke(environment(goodRevision), { outputDir, endpoints: site.endpoints, fetchImpl: site.fetchImpl, browserRunner });
            assert.equal(summary.status, "passed");
            assert.equal(summary.digestEvidenceOnly, true);
            assert.equal(summary.digestHttpProven, false);
            assert.equal(summary.hosts.length, 2);
            for (const host of ["private", "public"]) {
                await stat(join(outputDir, host, "headers.json"));
                await stat(join(outputDir, host, "browser-log.json"));
                await stat(join(outputDir, host, "screenshot.png"));
            }
            assert.equal(JSON.parse(await readFile(join(outputDir, "summary.json"), "utf8")).expectedDigest, digest);
            assert.match(await readFile(join(outputDir, "junit.xml"), "utf8"), /failures="0"/);
        });
    } finally {
        await site.close();
        await rm(temporary, { recursive: true, force: true });
    }
});

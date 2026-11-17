import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseEndpointList } from "./endpoint-list.mjs";
import { ParserWorkerSmokeFailure, runParserWorkerFlow } from "./production-package-smoke.mjs";

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const approvedLinks = ["https://github.com/JetBrains/hexana", "https://jetbrains.github.io/hexana/"];
const targetEndpointVariables = { master: "HEXANA_WEBAPP_MASTER_ENDPOINTS", production: "HEXANA_WEBAPP_PRODUCTION_ENDPOINTS" };
const targetEndpointCounts = { master: 1, production: 2 };
const requiredHeaders = {
    "content-security-policy": "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://eu.i.posthog.com; img-src 'self' data:; worker-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    "permissions-policy": "geolocation=(), microphone=(), camera=(), payment=(), usb=()",
    "x-frame-options": "DENY",
    "cross-origin-opener-policy": "same-origin",
    "cross-origin-embedder-policy": "require-corp",
    "cross-origin-resource-policy": "same-origin",
};

export class SmokeFailure extends Error {
    constructor(phase, message, host, cause) {
        super(`${phase}${host ? ` [${host}]` : ""}: ${message}`, { cause });
        this.name = "SmokeFailure";
        this.phase = phase;
        this.host = host;
    }
}

export class BrowserSmokeFailure extends Error {
    constructor(phase, message, cause) {
        super(message, { cause });
        this.name = "BrowserSmokeFailure";
        this.phase = phase;
    }
}

function fail(phase, message, host, cause) { throw new SmokeFailure(phase, message, host, cause); }

function validateInput(env) {
    const target = env.HEXANA_WEBAPP_SMOKE_TARGET;
    const version = env.HEXANA_WEBAPP_EXPECTED_VERSION;
    const revision = env.HEXANA_WEBAPP_EXPECTED_REVISION;
    const digest = env.HEXANA_WEBAPP_EXPECTED_DIGEST;
    if (!(target in targetEndpointVariables)) fail("input", "HEXANA_WEBAPP_SMOKE_TARGET must be master or production");
    if (!/^[0-9a-f]{40}$/.test(revision || "")) fail("input", "HEXANA_WEBAPP_EXPECTED_REVISION must be a lowercase full 40-character SHA");
    if (!/^sha256:[0-9a-f]{64}$/.test(digest || "")) fail("input", "HEXANA_WEBAPP_EXPECTED_DIGEST must be a canonical sha256 digest");
    if (target === "master" && version !== revision) fail("input", "master expected version must equal its expected revision");
    if (target === "production" && !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version || "")) fail("input", "production expected version must be strict semantic versioning");
    return { target, version, revision, digest };
}

function endpointsFromEnv(input, env) {
    const name = targetEndpointVariables[input.target];
    try { return parseEndpointList(env[name], name).map(secureUrl => ({ name: new URL(secureUrl).hostname, secureUrl })); }
    catch (error) { return fail("input", error.message); }
}

function resolveEndpoints(input, injected, env) {
    const endpoints = injected ?? endpointsFromEnv(input, env);
    if (!Array.isArray(endpoints) || endpoints.length !== targetEndpointCounts[input.target]) fail("input", `${input.target} requires ${targetEndpointCounts[input.target]} endpoint(s)`);
    return endpoints.map((endpoint, index) => {
        const secure = new URL(endpoint.secureUrl);
        const insecure = endpoint.httpUrl ? new URL(endpoint.httpUrl) : new URL(secure);
        if (!endpoint.httpUrl) insecure.protocol = "http:";
        return { name: endpoint.name || `host-${index + 1}`, secureUrl: secure.href, httpUrl: insecure.href };
    });
}

function responseRecord(label, url, response) {
    return { label, url, status: response.status, headers: Object.fromEntries([...response.headers].sort(([a], [b]) => a.localeCompare(b))) };
}

function checkHeaders(response, cacheControl, host) {
    for (const [name, expected] of Object.entries(requiredHeaders)) {
        if (response.headers.get(name) !== expected) fail("http/header", `${name} mismatch for ${response.url || "response"}`, host);
    }
    if (response.headers.get("cache-control") !== cacheControl) fail("http/header", `Cache-Control must be ${cacheControl}`, host);
}

async function request(fetchImpl, endpoint, relative, label, evidence) {
    const url = new URL(relative, endpoint.secureUrl).href;
    let response;
    try { response = await fetchImpl(url, { redirect: "manual" }); }
    catch (error) { fail("http/request", `${label} request failed: ${error.message}`, endpoint.name, error); }
    evidence.push(responseRecord(label, url, response));
    if (response.status >= 300 && response.status < 400) fail("http/redirect", `${label} escaped through redirect ${response.status}`, endpoint.name);
    if (response.url && new URL(response.url).origin !== new URL(endpoint.secureUrl).origin) fail("http/redirect", `${label} escaped to ${response.url}`, endpoint.name);
    return response;
}

function parseReleaseInfo(html, host) {
    const match = /<script\b(?=[^>]*\bid=["']hexana-release-info["'])[^>]*>([\s\S]*?)<\/script>/i.exec(html);
    if (!match) fail("http/identity", "release information script is missing", host);
    try { return JSON.parse(match[1]); }
    catch (error) { fail("http/identity", "release information is not valid JSON", host, error); }
}

function checkIdentity(info, input, host) {
    if (info.version !== input.version || info.revision !== input.revision) fail("http/identity", `expected ${input.version}/${input.revision}, got ${info.version}/${info.revision}`, host);
    if (info.repositoryUrl !== approvedLinks[0] || info.websiteUrl !== approvedLinks[1]) fail("http/identity", "release information contains unapproved links", host);
    return { version: info.version, revision: info.revision };
}

function assetReferences(text, pageUrl) {
    const values = [...text.matchAll(/(?:src|href)=["']([^"']+)["']/gi)].map(match => match[1]);
    values.push(...[...text.matchAll(/\bassets\/[A-Za-z0-9_.-]+/g)].map(match => match[0]));
    return values.filter(value => !value.startsWith("data:")).map(value => new URL(value, pageUrl)).filter(url => url.pathname.includes("/assets/"));
}

async function verifyEndpoint(endpoint, input, fetchImpl, evidence) {
    let redirect;
    try { redirect = await fetchImpl(endpoint.httpUrl, { redirect: "manual" }); }
    catch (error) { fail("http/request", `HTTP redirect request failed: ${error.message}`, endpoint.name, error); }
    evidence.push(responseRecord("http-redirect", endpoint.httpUrl, redirect));
    const location = redirect.headers.get("location");
    if (![301, 302, 307, 308].includes(redirect.status) || !location || new URL(location, endpoint.httpUrl).href !== endpoint.secureUrl) fail("http/redirect", `HTTP must redirect exactly to ${endpoint.secureUrl}`, endpoint.name);

    const health = await request(fetchImpl, endpoint, "healthz", "healthz", evidence);
    if (health.status !== 204 || (await health.arrayBuffer()).byteLength !== 0) fail("http/content", "/healthz must be 204 with an empty body", endpoint.name);
    checkHeaders(health, "no-store", endpoint.name);
    const robots = await request(fetchImpl, endpoint, "robots.txt", "robots", evidence);
    if (robots.status !== 200 || await robots.text() !== "User-agent: *\nDisallow: /\n") fail("http/content", "robots.txt must exactly disallow all crawling", endpoint.name);
    checkHeaders(robots, "no-store", endpoint.name);

    const pages = [];
    for (const [relative, label, cache] of [["", "root", "no-store"], ["index.html", "index", "no-cache"]]) {
        const response = await request(fetchImpl, endpoint, relative, label, evidence);
        if (response.status !== 200) fail("http/content", `${label} must return 200`, endpoint.name);
        checkHeaders(response, cache, endpoint.name);
        const html = await response.text();
        pages.push({ html, url: new URL(relative, endpoint.secureUrl).href, identity: checkIdentity(parseReleaseInfo(html, endpoint.name), input, endpoint.name) });
    }
    if (JSON.stringify(pages[0].identity) !== JSON.stringify(pages[1].identity)) fail("http/identity", "root and index.html release identities differ", endpoint.name);

    const queue = pages.flatMap(page => assetReferences(page.html, page.url));
    const seen = new Set();
    const assets = [];
    while (queue.length) {
        const url = queue.shift();
        if (seen.has(url.href)) continue;
        seen.add(url.href);
        if (url.origin !== new URL(endpoint.secureUrl).origin) fail("http/asset", `asset is not same-origin: ${url.href}`, endpoint.name);
        const filename = url.pathname.split("/").pop();
        const hash = /\.([0-9a-f]{16})\.[A-Za-z0-9]+$/.exec(filename || "")?.[1];
        if (!hash) fail("http/asset", `asset filename is not content-addressed: ${filename}`, endpoint.name);
        const response = await request(fetchImpl, endpoint, url.href, `asset:${filename}`, evidence);
        if (response.status !== 200) fail("http/asset", `${filename} must return 200`, endpoint.name);
        checkHeaders(response, "public, max-age=31536000, immutable", endpoint.name);
        const bytes = Buffer.from(await response.arrayBuffer());
        if (!bytes.length || !createHash("sha256").update(bytes).digest("hex").startsWith(hash)) fail("http/asset", `${filename} content does not match its hash`, endpoint.name);
        assets.push({ path: url.pathname, bytes: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex") });
        if (/\.(?:js|css)$/.test(filename)) queue.push(...assetReferences(bytes.toString("utf8"), endpoint.secureUrl));
    }
    if (!assets.length) fail("http/asset", "no immutable assets were discovered", endpoint.name);
    return { identity: pages[0].identity, assets };
}

export async function runCdpBrowser({ endpoint, input, wasmBytes }) {
    const browserLog = [];
    const externalRequests = [];
    let browser, profile, socket, cdp, screenshot;
    try {
        profile = await mkdtemp(join(tmpdir(), "hexana-live-smoke-"));
        const flags = process.env.CI || process.env.TEAMCITY_VERSION ? ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"] : [];
        for (const candidate of [process.env.CHROME_BIN, "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "google-chrome", "chromium", "chromium-browser"].filter(Boolean)) {
            const child = spawn(candidate, ["--headless=new", "--no-first-run", "--no-default-browser-check", ...flags, "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: "ignore" });
            const failed = await new Promise(resolve => { child.once("error", () => resolve(true)); setTimeout(() => resolve(child.exitCode !== null), 300); });
            if (!failed) { browser = child; break; }
        }
        if (!browser) throw new Error("Chrome/Chromium is required");
        let port;
        for (let attempt = 0; attempt < 80 && !port; attempt++) { try { port = Number((await readFile(join(profile, "DevToolsActivePort"), "utf8")).split("\n")[0]); } catch {} await delay(100); }
        let page;
        for (let attempt = 0; attempt < 80 && !page; attempt++) { try { page = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(target => target.type === "page"); } catch {} await delay(100); }
        if (!page) throw new Error("Chrome DevTools page was not available");
        socket = new WebSocket(page.webSocketDebuggerUrl);
        await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
        let nextId = 1;
        const pending = new Map();
        cdp = (method, params = {}) => new Promise((resolve, reject) => {
            const id = nextId++;
            pending.set(id, message => message.error ? reject(new Error(message.error.message)) : resolve(message.result));
            socket.send(JSON.stringify({ id, method, params }));
        });
        const origin = new URL(endpoint.secureUrl).origin;
        socket.onmessage = event => {
            const message = JSON.parse(event.data);
            if (message.id) { const settle = pending.get(message.id); if (settle) { pending.delete(message.id); settle(message); } return; }
            if (message.method === "Runtime.consoleAPICalled" || message.method === "Log.entryAdded") browserLog.push({ method: message.method, params: message.params });
            if (message.method === "Fetch.requestPaused") {
                const url = message.params.request.url;
                const protocol = new URL(url).protocol;
                const allowed = protocol === "data:" || protocol === "blob:" || (protocol.startsWith("http") && new URL(url).origin === origin);
                browserLog.push({ method: "request", url, allowed });
                if (!allowed) externalRequests.push(url);
                void cdp(allowed ? "Fetch.continueRequest" : "Fetch.failRequest", allowed ? { requestId: message.params.requestId } : { requestId: message.params.requestId, errorReason: "BlockedByClient" });
            }
        };
        await Promise.all([cdp("Page.enable"), cdp("Runtime.enable"), cdp("Log.enable"), cdp("Fetch.enable", { patterns: [{ urlPattern: "*" }] })]);
        await cdp("Page.addScriptToEvaluateOnNewDocument", { source: "localStorage.setItem('hexana.webapp.telemetryEnabled','false')" });
        const navigation = await cdp("Page.navigate", { url: endpoint.secureUrl });
        if (navigation.errorText) throw new Error(`navigation failed: ${navigation.errorText}`);
        const evaluate = async expression => {
            const result = await cdp("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
            if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
            return result.result?.value;
        };
        for (let attempt = 0; attempt < 120 && !await evaluate("document.readyState === 'complete'"); attempt++) await delay(100);
        if (await evaluate("localStorage.getItem('hexana.webapp.telemetryEnabled')") !== "false") throw new Error("telemetry was not disabled before app startup");
        const about = await evaluate(`(async () => { for (let i=0;i<100&&!document.querySelector('[data-about-trigger]');i++) await new Promise(r=>setTimeout(r,100)); const trigger=document.querySelector('[data-about-trigger]'); if(!trigger)return null; trigger.click(); await new Promise(r=>setTimeout(r,50)); const value=s=>document.querySelector(s)?.textContent?.trim(); return {version:value('[data-about-version]'),revision:value('[data-about-revision]'),links:[...document.querySelectorAll('[data-about-link]')].map(a=>a.href)}; })()`);
        if (about?.version !== `Version: ${input.version}` || about?.revision !== `Revision: ${input.revision}` || JSON.stringify(about.links) !== JSON.stringify(approvedLinks)) throw new BrowserSmokeFailure("identity", `About identity or links mismatch: ${JSON.stringify(about)}`);
        try { await runParserWorkerFlow(evaluate, wasmBytes); }
        catch (error) { throw new BrowserSmokeFailure(`parser-worker/${error instanceof ParserWorkerSmokeFailure ? error.reason : "operational"}`, error.message, error); }
        if (externalRequests.length) throw new Error(`blocked external requests: ${externalRequests.join(", ")}`);
        screenshot = Buffer.from((await cdp("Page.captureScreenshot", { format: "png" })).data, "base64");
        return { browserLog, screenshot };
    } catch (error) {
        if (!screenshot && cdp) { try { screenshot = Buffer.from((await cdp("Page.captureScreenshot", { format: "png" })).data, "base64"); } catch {} }
        error.browserLog = browserLog;
        error.screenshot = screenshot;
        throw error;
    } finally {
        if (socket) socket.close();
        if (browser?.exitCode === null) { browser.kill(); await new Promise(resolve => browser.once("exit", resolve)); }
        if (profile) await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
    }
}

function xml(value) { return String(value).replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&apos;" })[character]); }

export async function runLiveSmoke(env = process.env, options = {}) {
    const outputDir = options.outputDir || new URL("./build/live-smoke/", import.meta.url).pathname;
    const startedAt = new Date().toISOString();
    let input, endpoints, failure;
    const hosts = [];
    try {
        await rm(outputDir, { recursive: true, force: true });
        await mkdir(outputDir, { recursive: true });
        input = validateInput(env);
        endpoints = resolveEndpoints(input, options.endpoints, env);
        const wasmBytes = await readFile(options.wasmFixture || new URL("../wasmParser/src/commonTest/resources/snippets/addition.wasm", import.meta.url));
        for (const endpoint of endpoints) {
            const directory = join(outputDir, endpoint.name.replace(/[^A-Za-z0-9_.-]/g, "_"));
            const headers = [];
            await mkdir(directory, { recursive: true });
            try {
                const http = await verifyEndpoint(endpoint, input, options.fetchImpl || fetch, headers);
                await writeFile(join(directory, "headers.json"), JSON.stringify(headers, null, 2) + "\n");
                let browser;
                try { browser = await (options.browserRunner || runCdpBrowser)({ endpoint, input, wasmBytes }); }
                catch (error) {
                    if (error.browserLog) await writeFile(join(directory, "browser-log.json"), JSON.stringify(error.browserLog, null, 2) + "\n");
                    if (error.screenshot) await writeFile(join(directory, "screenshot.png"), error.screenshot);
                    fail(error instanceof BrowserSmokeFailure ? `browser/${error.phase}` : "browser", error.message, endpoint.name, error);
                }
                await writeFile(join(directory, "browser-log.json"), JSON.stringify(browser.browserLog || [], null, 2) + "\n");
                if (!browser.screenshot?.length) fail("browser", "browser runner did not return a screenshot", endpoint.name);
                await writeFile(join(directory, "screenshot.png"), browser.screenshot);
                hosts.push({ name: endpoint.name, url: endpoint.secureUrl, identity: http.identity, assets: http.assets, status: "passed" });
            } catch (error) {
                await writeFile(join(directory, "headers.json"), JSON.stringify(headers, null, 2) + "\n");
                throw error;
            }
        }
        if (input.target === "production" && new Set(hosts.map(host => JSON.stringify(host.identity))).size !== 1) fail("http/identity", "production host identities differ");
    } catch (error) {
        failure = error instanceof SmokeFailure ? error : new SmokeFailure("operational", error.message, undefined, error);
    }
    await mkdir(outputDir, { recursive: true });
    const summary = { schemaVersion: 1, target: input?.target, expectedVersion: input?.version, expectedRevision: input?.revision, expectedDigest: input?.digest, digestEvidenceOnly: true, digestHttpProven: false, startedAt, finishedAt: new Date().toISOString(), status: failure ? "failed" : "passed", failure: failure ? { phase: failure.phase, host: failure.host, message: failure.message } : null, hosts };
    await writeFile(join(outputDir, "summary.json"), JSON.stringify(summary, null, 2) + "\n");
    const failureXml = failure ? `<failure type="${xml(failure.phase)}" message="${xml(failure.message)}">${xml(failure.stack || failure.message)}</failure>` : "";
    await writeFile(join(outputDir, "junit.xml"), `<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="Hexana live webapp smoke" tests="1" failures="${failure ? 1 : 0}"><testcase name="${xml(input?.target || "input-validation")}">${failureXml}</testcase></testsuite>\n`);
    if (failure) throw failure;
    return summary;
}

if (process.argv[1] === new URL(import.meta.url).pathname) await runLiveSmoke();

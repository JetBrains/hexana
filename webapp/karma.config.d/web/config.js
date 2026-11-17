const path = require("node:path");
const zlib = require("node:zlib");

const fakePostHogRequests = [];
const fakePostHogPrefix = "/__fake-posthog";

function decodeFakePostHogBody(body, contentEncoding) {
    const encodings = Array.isArray(contentEncoding) ? contentEncoding : [contentEncoding];
    const isGzip = encodings.some(encoding => encoding?.toLowerCase() === "gzip") ||
        body.length >= 2 && body[0] === 0x1f && body[1] === 0x8b;
    return (isGzip ? zlib.gunzipSync(body) : body).toString("utf8");
}

function fakePostHogEvents() {
    return fakePostHogRequests.flatMap(request => {
        if (!request.body.trim()) return [];
        let payload;
        try {
            payload = JSON.parse(request.body);
        } catch {
            return [];
        }
        const candidates = Array.isArray(payload.batch) ? payload.batch :
            Array.isArray(payload.events) ? payload.events : [payload];
        return candidates
            .filter(candidate => candidate && typeof candidate.event === "string")
            .map(candidate => ({
                name: candidate.event,
                distinctId: candidate.distinct_id ?? candidate.distinctId,
                properties: candidate.properties ?? {},
            }));
    });
}

function respondFakePostHog(response, status, payload) {
    const body = Buffer.from(JSON.stringify(payload), "utf8");
    response.writeHead(status, {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Headers": "Content-Type",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        "Content-Type": "application/json",
        "Content-Length": String(body.length),
    });
    response.end(body);
}

function handleFakePostHogRequest(request, response, pathname) {
    if (request.method === "OPTIONS") {
        respondFakePostHog(response, 200, { status: "ok" });
        return;
    }
    if (pathname === `${fakePostHogPrefix}/__events`) {
        respondFakePostHog(response, 200, { events: fakePostHogEvents() });
        return;
    }
    if (pathname === `${fakePostHogPrefix}/__reset`) {
        fakePostHogRequests.length = 0;
        respondFakePostHog(response, 200, { status: "ok" });
        return;
    }
    if (pathname.endsWith("/decide")) {
        respondFakePostHog(response, 200, { config: {} });
        return;
    }
    const chunks = [];
    request.on("data", chunk => chunks.push(Buffer.from(chunk)));
    request.on("end", () => {
        try {
            fakePostHogRequests.push({
                method: request.method,
                path: pathname,
                body: decodeFakePostHogBody(Buffer.concat(chunks), request.headers["content-encoding"]),
            });
            respondFakePostHog(response, 200, { status: "ok" });
        } catch (error) {
            respondFakePostHog(response, 500, { error: String(error) });
        }
    });
}

function fakePostHogMiddleware(request, response, next) {
    const pathname = new URL(request.url, "http://127.0.0.1").pathname;
    if (!pathname.startsWith(fakePostHogPrefix)) {
        next();
        return;
    }
    handleFakePostHogRequest(request, response, pathname);
}

fakePostHogMiddleware.$inject = [];

function createFakePostHogMiddleware() {
    return fakePostHogMiddleware;
}

createFakePostHogMiddleware.$inject = [];

function fakePostHogReporter() {
    this.onRunComplete = (_browsers, results) => {
        if (!runsUiTelemetryTest) return;
        const events = fakePostHogEvents();
        const binaryOpened = events.find(event =>
            event.name === "binary_opened" &&
            event.properties.surface === "webapp" &&
            event.properties.file_size_bytes === 8 &&
            event.properties.format === "wasm",
        );
        const unexpectedEvents = events.filter(event => event.name !== "binary_opened");
        const unexpectedProperties = binaryOpened && Object.keys(binaryOpened.properties).filter(key =>
            !["distinct_id", "format", "file_size_bytes", "surface"].includes(key),
        );
        if (!binaryOpened || unexpectedEvents.length > 0 || unexpectedProperties?.length > 0) {
            results.exitCode = 1;
            console.error(
                "Fake PostHog received unexpected webapp telemetry: " + JSON.stringify(events),
            );
        }
    };
}

fakePostHogReporter.$inject = [];

const uiTelemetryTest = "org.jetbrains.hexana.webapp.WebAppTelemetryTest.browserUiSendsOptedInEventToFakePostHogServer";
const selectedTestArgs = config.client?.args || [];
const runsUiTelemetryTest = !selectedTestArgs.includes("--include") || selectedTestArgs.includes(uiTelemetryTest);

config.plugins = [...(config.plugins || []), {
    "reporter:fakePostHog": ["type", fakePostHogReporter],
    "middleware:fakePostHog": ["factory", createFakePostHogMiddleware],
}];
config.reporters = [...(config.reporters || []), "fakePostHog"];
config.middleware = [...(config.middleware || []), "fakePostHog"];

config.files.push({
    pattern: path.resolve(config.basePath, "kotlin", "parser-worker.js"),
    included: false,
    served: true,
    watched: false,
});
config.files.push({
    pattern: path.resolve(config.basePath, "kotlin", "parser-worker-fixtures", "*"),
    included: false,
    served: true,
    watched: false,
});
// The real editor.css and a .NET assembly, for DockedTabsLayoutDomTest.
config.files.push({
    pattern: path.resolve(config.basePath, "kotlin", "layout-fixtures", "*"),
    included: false,
    served: true,
    watched: false,
});
config.proxies = {
    ...(config.proxies || {}),
    "/kotlin": "/base/kotlin/",
    "/parser-worker.js": "/base/kotlin/parser-worker.js",
};

const isCi = Boolean(process.env["CI"] || process.env["TEAMCITY_VERSION"]);
const chromiumBase = isCi ? "ChromiumHeadless" : "Chromium";
const chromeBase = isCi ? "ChromeHeadless" : "Chrome";
const ciFlags = ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"];

config.customLaunchers = {
    ...(config.customLaunchers || {}),
    ChromeForTests: {
        base: chromeBase,
        flags: isCi ? ciFlags : [],
    },
    ChromiumForTests: {
        base: chromiumBase,
        flags: isCi ? ciFlags : [],
    },
    FirefoxForTests: {
        base: "Firefox",
        prefs: {
            "datareporting.policy.dataSubmissionPolicyBypassNotification": true,
        },
    },
    SafariForTests: {
        base: "Safari",
    },
};

config.browsers = [];
if (process.env["hx.tests.web.enableChromeOrChromium"]) {
    config.browsers.push(process.platform === "linux" ? "ChromiumForTests" : "ChromeForTests");
}
if (process.env["hx.tests.web.enableFirefox"]) config.browsers.push("FirefoxForTests");
if (process.env["hx.tests.web.enableSafari"]) config.browsers.push("SafariForTests");

config.browserDisconnectTimeout = 30000;
config.browserNoActivityTimeout = 120000;
config.captureTimeout = 120000;

config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 120000;
if (process.env["HX_WEB_PARSER_WORKER_MEASUREMENTS"] !== "1") {
    config.client.mocha.grep = "ParserWorkerMeasurementTest";
    config.client.mocha.invert = true;
}

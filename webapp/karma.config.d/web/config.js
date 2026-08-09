const path = require("node:path");

config.files.push({
    pattern: path.resolve(config.basePath, "kotlin", "parser-worker.js"),
    included: false,
    served: true,
    watched: false,
});
config.files.push({
    pattern: path.resolve(config.basePath, "kotlin", "parser-worker-fixtures", "*.wasm"),
    included: false,
    served: true,
    watched: false,
    type: "wasm",
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

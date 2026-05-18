const path = require("node:path");
const {basePath} = config;

const wasmResourcesPath = path.resolve(basePath, "kotlin", "**", "*.wasm");
const watResourcesPath = path.resolve(basePath, "kotlin", "**", "*.wat");

config.files.push(
    {
        pattern: wasmResourcesPath,
        included: false,
        served: true,
        watched: false,
        type: "wasm"
    },
    {
        pattern: watResourcesPath,
        included: false,
        served: true,
        watched: false,
    },
);

config.proxies = {
    "/kotlin": "/base/kotlin/",
}

function configLaunchers(config) {
    const isCi = Boolean(process.env["CI"] || process.env["TEAMCITY_VERSION"]);
    const chromiumBase = isCi ? "ChromiumHeadless" : "Chromium";
    const chromeBase = isCi ? "ChromeHeadless" : "Chrome";
    const chromeFlags = [
        "--disable-search-engine-choice-screen",
        ...(isCi ? ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"] : [])
    ];

    config.customLaunchers = {
        ChromeForTests: {
            base: chromeBase,
            flags: chromeFlags
        },
        ChromiumForTests: {
            base: chromiumBase,
            flags: isCi ? ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"] : []
        },
        FirefoxForTests: {
            base: "Firefox",
            prefs: {
                // https://firefox-source-docs.mozilla.org/toolkit/components/telemetry/internals/preferences.html
                // Records the version of the policy notified to the user. This preference is also used on Android, used in tests, it allows to skip the notification check.
                'datareporting.policy.dataSubmissionPolicyBypassNotification': true
            }
        },
        SafariForTests: {
            base: "Safari"
        }
    }

    config.browsers = [];


    if (process.env["hx.tests.web.enableChromeOrChromium"]) {
        const isLinux = process.platform === "linux";
        if (isLinux) {
            config.browsers.push("ChromiumForTests");
        } else {
            config.browsers.push("ChromeForTests");
        }
    }
    if (process.env["hx.tests.web.enableFirefox"]) {
        config.browsers.push("FirefoxForTests");
    }
    if (process.env["hx.tests.web.enableSafari"]) {
        config.browsers.push("SafariForTests");
    }

    console.log("Browsers: " + config.browsers);
}

configLaunchers(config);

config.browserDisconnectTimeout = 360000;
config.browserNoActivityTimeout = 360000;
config.captureTimeout = 360000;

config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 360000;

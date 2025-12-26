const path = require("node:path");
const {basePath} = config;

const resourcesPath = path.resolve(basePath, "kotlin", "**", "*.wasm");

// karma use minimatch and glob libraries that actually ignore windows path conventions
// so, we have to sort of "undo" path to make tests work on Windows
const resourcePathNormalized = resourcesPath.replace(/\\/g, '/');

config.files.push(
    {
        pattern: resourcePathNormalized,
        included: false,
        served: true,
        watched: false,
        type: "wasm"
    },
);

config.proxies = {
    "/kotlin": "/base/kotlin/",
}


function configLaunchers(config) {
    config.customLaunchers = {
        ChromeForTests: {
            base: "Chrome",
            flags: ["--disable-search-engine-choice-screen"]
        },
        ChromiumForTests: {
            base: "Chromium"
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

config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 30000;

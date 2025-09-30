const path = require("node:path");
const {basePath} = config;

config.files.push(
    {
        pattern: `${path.resolve(basePath, "kotlin")}/**/*.wasm`,
        included: false,
        served: true,
        watched: false,
        type: "wasm"
    },
);

config.proxies = {
    "/kotlin": path.resolve(basePath, "kotlin"),
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
            base: "Firefox"
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
config.client.mocha.timeout = 12900;

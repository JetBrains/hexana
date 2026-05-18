function configLaunchers(config) {
    const isCi = Boolean(process.env["CI"] || process.env["TEAMCITY_VERSION"]);
    const chromiumBase = isCi ? "ChromiumHeadless" : "Chromium";
    const chromeBase = isCi ? "ChromeHeadless" : "Chrome";
    const ciFlags = ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu"];

    config.customLaunchers = {
        ...(config.customLaunchers || {}),
        ChromeForTests: {
            base: chromeBase,
            flags: isCi ? ciFlags : []
        },
        ChromiumForTests: {
            base: chromiumBase,
            flags: isCi ? ciFlags : []
        },
        FirefoxForTests: {
            base: "Firefox",
            prefs: {
                // Skip Firefox's telemetry policy notification in automated runs.
                "datareporting.policy.dataSubmissionPolicyBypassNotification": true
            }
        },
        SafariForTests: {
            base: "Safari"
        }
    };

    config.browsers = [];

    if (process.env["hx.tests.web.enableChromeOrChromium"]) {
        const isLinux = process.platform === "linux";
        config.browsers.push(isLinux ? "ChromiumForTests" : "ChromeForTests");
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

config.browserDisconnectTimeout = 30000;
config.browserNoActivityTimeout = 120000;
config.captureTimeout = 120000;

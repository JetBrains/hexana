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
        }
    };

    if (process.env["hx.tests.web.enableChromeOrChromium"]) {
        const isLinux = process.platform === "linux";
        config.browsers = [isLinux ? "ChromiumForTests" : "ChromeForTests"];
    }
}

configLaunchers(config);

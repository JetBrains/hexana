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


config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 12900;

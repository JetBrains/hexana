#!/usr/bin/env node

const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const path = require("path");

const port = Number(process.env.PORT ?? 18765);
const failArchive = process.env.HEXANA_MCP_MOCK_FAIL_ARCHIVE === "1";
const requestLogPath = process.env.HEXANA_MCP_MOCK_REQUEST_LOG;
const defaultVersion = readMcpServerVersion();
const version = process.argv[3] ?? defaultVersion;
const platform = process.env.HEXANA_MCP_MOCK_PLATFORM ?? currentDownloadsPlatform();
const archivePath = process.argv[2] ?? defaultArchivePath(version, platform);

if (!archivePath || !platform) {
    console.error("Usage: node scripts/mock-mcp-downloads-server.js [path-to-mcp-zip] [version]");
    process.exit(1);
}

const absoluteArchivePath = path.resolve(archivePath);
const archiveBytes = fs.readFileSync(absoluteArchivePath);
const archiveFileName = path.basename(absoluteArchivePath);
const artifactName = /^hexana-mcp-native-.+\.zip$/.test(archiveFileName)
    ? archiveFileName
    : `hexana-mcp-native-${platform}-${version}.zip`;
const sha256 = crypto.createHash("sha256").update(archiveBytes).digest("hex");

const server = http.createServer((request, response) => {
    const requestUrl = new URL(request.url ?? "/", `http://127.0.0.1:${port}`);
    appendRequestLog(`${request.method ?? "GET"} ${requestUrl.pathname}`);

    if (requestUrl.pathname === "/resources/hexana/mcp/releases.json") {
        responseJson(response, {
            schemaVersion: 1,
            latest: version,
            downloadsRoot: `http://127.0.0.1:${port}/resources/hexana/mcp`,
            generatedAt: new Date(0).toISOString(),
            releases: [
                {
                    version,
                    manifestUrl: `http://127.0.0.1:${port}/resources/hexana/mcp/${version}/hexana-mcp-release-${version}.json`,
                    downloadsPrefix: `http://127.0.0.1:${port}/resources/hexana/mcp/${version}`,
                    generatedAt: new Date(0).toISOString(),
                    gitRevision: null,
                    teamcity: {
                        buildId: null,
                        buildUrl: null,
                    },
                    platforms: [platform],
                    artifactKinds: ["native-executable"],
                },
            ],
        });
        return;
    }

    if (requestUrl.pathname === `/resources/hexana/mcp/${version}/hexana-mcp-release-${version}.json`) {
        responseJson(response, {
            version,
            gitRevision: null,
            teamcity: {
                buildId: null,
                buildUrl: null,
            },
            downloadsPrefix: `http://127.0.0.1:${port}/resources/hexana/mcp/${version}`,
            generatedAt: new Date(0).toISOString(),
            artifacts: [
                {
                    name: artifactName,
                    platform,
                    kind: "native-executable",
                    size: archiveBytes.length,
                    sha256,
                    githubReleaseAssetUrl: null,
                    downloadsUrl: `http://127.0.0.1:${port}/resources/hexana/mcp/${version}/${encodeURIComponent(artifactName)}`,
                },
            ],
        });
        return;
    }

    if (requestUrl.pathname === `/resources/hexana/mcp/${version}/${encodeURIComponent(artifactName)}`) {
        if (failArchive) {
            appendRequestLog(`DOWNLOAD_STATUS 500 ${requestUrl.pathname}`);
            response.writeHead(500, {
                "Content-Type": "text/plain; charset=utf-8",
            });
            response.end("Intentional mock archive download failure");
            return;
        }

        appendRequestLog(`DOWNLOAD_STATUS 200 ${requestUrl.pathname}`);
        response.writeHead(200, {
            "Content-Type": "application/zip",
            "Content-Length": archiveBytes.length,
        });
        response.end(archiveBytes);
        return;
    }

    response.writeHead(404, {
        "Content-Type": "text/plain; charset=utf-8",
    });
    response.end("Not found");
});

server.listen(port, "127.0.0.1", () => {
    console.log(`Mock MCP downloads index: http://127.0.0.1:${port}/resources/hexana/mcp/releases.json`);
    console.log(`Mock downloads page: http://127.0.0.1:${port}/resources/hexana/mcp`);
    console.log(`Serving ${absoluteArchivePath}`);
    console.log(`Version ${version}`);
    console.log(`Platform ${platform}`);
    console.log(`SHA-256 ${sha256}`);
    if (failArchive) {
        console.log("Archive download failure mode enabled");
    }
});

function responseJson(response, value) {
    response.writeHead(200, {
        "Content-Type": "application/json; charset=utf-8",
    });
    response.end(JSON.stringify(value, null, 2));
}

function readMcpServerVersion() {
    const propertiesPath = path.resolve(__dirname, "../../mcp/server/gradle.properties");
    const properties = fs.readFileSync(propertiesPath, "utf8");
    const match = /^mcpServerVersion=(.+)$/m.exec(properties);
    if (!match) {
        throw new Error(`Could not read mcpServerVersion from ${propertiesPath}`);
    }
    return match[1].trim();
}

function currentDownloadsPlatform() {
    const osName = process.platform === "darwin"
        ? "macos"
        : process.platform === "linux"
            ? "linux"
            : process.platform === "win32"
                ? "windows"
                : undefined;
    const archName = process.arch === "x64" || process.arch === "arm64"
        ? process.arch
        : undefined;
    return osName && archName ? `${osName}-${archName}` : undefined;
}

function defaultArchivePath(version, platform) {
    const nativeArchivePath = path.resolve(
        __dirname,
        `../../mcp/build/distributions-native/hexana-mcp-native-${platform}-${version}.zip`,
    );
    if (fs.existsSync(nativeArchivePath)) {
        return nativeArchivePath;
    }

    return path.resolve(
        __dirname,
        `../../mcp/server/build/distributions/server-${version}.zip`,
    );
}

function appendRequestLog(line) {
    if (!requestLogPath) {
        return;
    }
    fs.appendFileSync(requestLogPath, `${line}\n`, "utf8");
}

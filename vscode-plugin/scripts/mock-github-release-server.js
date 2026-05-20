#!/usr/bin/env node

const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const path = require("path");

const port = Number(process.env.PORT ?? 18765);
const failArchive = process.env.HEXANA_MCP_MOCK_FAIL_ARCHIVE === "1";
const requestLogPath = process.env.HEXANA_MCP_MOCK_REQUEST_LOG;
const defaultVersion = readMcpServerVersion();
const archivePath = process.argv[2] ?? defaultMcpServerArchivePath(defaultVersion);
const version = process.argv[3] ?? defaultVersion;

if (!archivePath) {
    console.error("Usage: node scripts/mock-github-release-server.js [path-to-server-zip] [version]");
    process.exit(1);
}

const absoluteArchivePath = path.resolve(archivePath);
const archiveBytes = fs.readFileSync(absoluteArchivePath);
const archiveFileName = path.basename(absoluteArchivePath);
const sha256 = crypto.createHash("sha256").update(archiveBytes).digest("hex");

const server = http.createServer((request, response) => {
    const requestUrl = new URL(request.url ?? "/", `http://127.0.0.1:${port}`);
    appendRequestLog(`${request.method ?? "GET"} ${requestUrl.pathname}`);

    if (requestUrl.pathname.startsWith("/repos/example/hexana-mcp/releases/tags/")) {
        const release = {
            tag_name: version,
            assets: [
                {
                    name: archiveFileName,
                    browser_download_url: `http://127.0.0.1:${port}/download/${encodeURIComponent(archiveFileName)}`,
                    digest: `sha256:${sha256}`,
                },
            ],
        };

        response.writeHead(200, {
            "Content-Type": "application/json; charset=utf-8",
        });
        response.end(JSON.stringify(release, null, 2));
        return;
    }

    if (requestUrl.pathname === `/download/${encodeURIComponent(archiveFileName)}`) {
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
    console.log(`Mock GitHub release API: http://127.0.0.1:${port}/repos/example/hexana-mcp/releases/tags/<tag>`);
    console.log(`Mock releases page: http://127.0.0.1:${port}/releases`);
    console.log(`Serving ${absoluteArchivePath}`);
    console.log(`Version ${version}`);
    console.log(`SHA-256 ${sha256}`);
    if (failArchive) {
        console.log("Archive download failure mode enabled");
    }
});

function readMcpServerVersion() {
    const propertiesPath = path.resolve(__dirname, "../../mcp/server/gradle.properties");
    const properties = fs.readFileSync(propertiesPath, "utf8");
    const match = /^mcpServerVersion=(.+)$/m.exec(properties);
    if (!match) {
        throw new Error(`Could not read mcpServerVersion from ${propertiesPath}`);
    }
    return match[1].trim();
}

function defaultMcpServerArchivePath(version) {
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

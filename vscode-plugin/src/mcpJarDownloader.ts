import * as crypto from "crypto";
import * as fs from "fs";
import * as https from "https";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";

const githubLatestReleaseApiUrl = "https://api.github.com/repos/example/hexana-mcp/releases/latest";
const githubReleasesPageUrl = "https://github.com/example/hexana-mcp/releases";
const fallbackMcpJarVersion = "0.1.0-example";
const fallbackMcpJarFileName = `hexana-vscode-mcp-server-${fallbackMcpJarVersion}.jar`;
const fallbackMcpJarDownloadUrl = `https://example.com/hexana/mcp/${fallbackMcpJarFileName}`;
const fallbackSha256 = "0000000000000000000000000000000000000000000000000000000000000000";

const storageDirName = "mcp";
const installedJarFileName = "hexana-vscode-mcp-server.jar";
const versionMarkerFileName = "server.version";
const lockFileName = ".download.lock";

interface PrepareOptions {
    force?: boolean;
}

interface HexanaMcpRelease {
    version: string;
    downloadUrl: string;
    sha256: string;
}

interface HexanaMcpJarStatus {
    expectedVersion: string;
    downloaded: boolean;
    version?: string;
    jarUri?: vscode.Uri;
}

export function registerHexanaMcpJarCommands(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.commands.registerCommand("hexana.mcp.updateServer", async () => {
            const jarUri = await prepareHexanaMcpJar(context, { force: true });
            if (jarUri) {
                const status = await getHexanaMcpJarStatus(context);
                void vscode.window.showInformationMessage(`Hexana MCP server is updated to ${status.expectedVersion}.`);
            }
        }),
        vscode.commands.registerCommand("hexana.mcp.showServerStatus", async () => {
            const status = await getHexanaMcpJarStatus(context);
            const message = formatStatusMessage(status);
            const actions = status.downloaded ? ["Update", "Reveal JAR"] : ["Update"];
            const action = await vscode.window.showInformationMessage(message, ...actions);

            if (action === "Update") {
                void vscode.commands.executeCommand("hexana.mcp.updateServer");
            } else if (action === "Reveal JAR" && status.jarUri) {
                void vscode.commands.executeCommand("revealFileInOS", status.jarUri);
            }
        }),
    );
}

export async function prepareHexanaMcpJar(
    context: vscode.ExtensionContext,
    options: PrepareOptions = {},
): Promise<vscode.Uri | undefined> {
    try {
        return await ensureHexanaMcpJar(context, options);
    } catch (error) {
        console.warn("[Hexana] MCP server JAR was not prepared", error);
        void vscode.window.showInformationMessage(
            "Hexana MCP server could not be downloaded automatically.",
            "Retry",
            "Open Download Page",
        ).then((action) => {
            if (action === "Retry") {
                void prepareHexanaMcpJar(context, options);
            } else if (action === "Open Download Page") {
                void vscode.env.openExternal(vscode.Uri.parse(githubReleasesPageUrl));
            }
        });
        return undefined;
    }
}

async function getHexanaMcpJarStatus(context: vscode.ExtensionContext): Promise<HexanaMcpJarStatus> {
    const latestRelease = await getLatestReleaseOrFallback();
    const storageDir = path.join(context.globalStorageUri.fsPath, storageDirName);
    const jarPath = path.join(storageDir, installedJarFileName);
    const markerPath = path.join(storageDir, versionMarkerFileName);

    try {
        const [jarStat, marker] = await Promise.all([
            fs.promises.stat(jarPath),
            fs.promises.readFile(markerPath, "utf8"),
        ]);
        const version = marker.trim();
        return {
            expectedVersion: latestRelease.version,
            downloaded: jarStat.isFile() && version === latestRelease.version,
            version,
            jarUri: jarStat.isFile() ? vscode.Uri.file(jarPath) : undefined,
        };
    } catch {
        return {
            expectedVersion: latestRelease.version,
            downloaded: false,
        };
    }
}

function formatStatusMessage(status: HexanaMcpJarStatus): string {
    if (status.downloaded) {
        return `Hexana MCP server: ${status.version}. Latest known version: ${status.expectedVersion}.`;
    }

    if (status.version) {
        return `Hexana MCP server is not ready. Installed version: ${status.version}. Latest known version: ${status.expectedVersion}.`;
    }

    return `Hexana MCP server is not downloaded. Latest known version: ${status.expectedVersion}.`;
}

async function ensureHexanaMcpJar(
    context: vscode.ExtensionContext,
    options: PrepareOptions,
): Promise<vscode.Uri> {
    const latestRelease = await getLatestReleaseOrFallback();
    const storageDir = path.join(context.globalStorageUri.fsPath, storageDirName);
    const jarPath = path.join(storageDir, installedJarFileName);
    const markerPath = path.join(storageDir, versionMarkerFileName);

    await fs.promises.mkdir(storageDir, { recursive: true });

    if (!options.force && await isPrepared(jarPath, markerPath, latestRelease.version)) {
        return vscode.Uri.file(jarPath);
    }

    return withDownloadLock(storageDir, async () => {
        if (!options.force && await isPrepared(jarPath, markerPath, latestRelease.version)) {
            return vscode.Uri.file(jarPath);
        }

        await vscode.window.withProgress(
            {
                location: vscode.ProgressLocation.Notification,
                title: "Getting Hexana MCP ready...",
                cancellable: false,
            },
            async (progress) => {
                const tempJarPath = path.join(
                    os.tmpdir(),
                    `hexana-mcp-${process.pid}-${Date.now()}-${crypto.randomBytes(6).toString("hex")}.jar`,
                );

                try {
                    progress.report({ increment: 5, message: "Downloading..." });
                    await downloadFile(latestRelease.downloadUrl, tempJarPath);

                    progress.report({ increment: 70, message: "Verifying integrity..." });
                    await verifySha256(tempJarPath, latestRelease.sha256);

                    await fs.promises.mkdir(storageDir, { recursive: true });
                    await fs.promises.rm(jarPath, { force: true });
                    await fs.promises.rename(tempJarPath, jarPath);
                    await fs.promises.writeFile(markerPath, `${latestRelease.version}\n`, "utf8");
                    progress.report({ increment: 25 });
                } finally {
                    await fs.promises.rm(tempJarPath, { force: true });
                }
            },
        );

        return vscode.Uri.file(jarPath);
    });
}

async function isPrepared(jarPath: string, markerPath: string, expectedVersion: string): Promise<boolean> {
    try {
        const [jarStat, marker] = await Promise.all([
            fs.promises.stat(jarPath),
            fs.promises.readFile(markerPath, "utf8"),
        ]);
        return jarStat.isFile() && marker.trim() === expectedVersion;
    } catch {
        return false;
    }
}

async function getLatestReleaseOrFallback(): Promise<HexanaMcpRelease> {
    try {
        return await fetchLatestRelease();
    } catch (error) {
        console.warn("[Hexana] Could not fetch latest MCP release metadata, using fallback", error);
        return {
            version: fallbackMcpJarVersion,
            downloadUrl: fallbackMcpJarDownloadUrl,
            sha256: fallbackSha256,
        };
    }
}

async function fetchLatestRelease(): Promise<HexanaMcpRelease> {
    const release = await getJson<GitHubRelease>(githubLatestReleaseApiUrl);
    const asset = release.assets.find((candidate) => candidate.name.endsWith(".jar"));

    if (!asset) {
        throw new Error("Latest GitHub release does not contain a .jar asset");
    }

    const sha256 = parseGitHubSha256Digest(asset.digest);
    if (!sha256) {
        throw new Error(`GitHub release asset ${asset.name} does not provide a SHA-256 digest`);
    }

    return {
        version: release.tag_name,
        downloadUrl: asset.browser_download_url,
        sha256,
    };
}

interface GitHubRelease {
    tag_name: string;
    assets: GitHubReleaseAsset[];
}

interface GitHubReleaseAsset {
    name: string;
    browser_download_url: string;
    digest?: string;
}

function getJson<T>(url: string): Promise<T> {
    return new Promise((resolve, reject) => {
        const request = https.get(
            url,
            {
                headers: {
                    "Accept": "application/vnd.github+json",
                    "User-Agent": "Hexana-VSCode",
                    "X-GitHub-Api-Version": "2022-11-28",
                },
            },
            (response) => {
                const statusCode = response.statusCode ?? 0;
                const location = response.headers.location;

                if (statusCode >= 300 && statusCode < 400 && location) {
                    response.resume();
                    getJson<T>(new URL(location, url).toString()).then(resolve, reject);
                    return;
                }

                const chunks: Buffer[] = [];
                response.on("data", (chunk: Buffer) => chunks.push(chunk));
                response.on("end", () => {
                    const body = Buffer.concat(chunks).toString("utf8");
                    if (statusCode < 200 || statusCode >= 300) {
                        reject(new Error(`GitHub release metadata request failed with HTTP ${statusCode}: ${body}`));
                        return;
                    }

                    try {
                        resolve(JSON.parse(body) as T);
                    } catch (error) {
                        reject(error);
                    }
                });
            },
        );

        request.setTimeout(30_000, () => {
            request.destroy(new Error("GitHub release metadata request timed out"));
        });
        request.on("error", reject);
        request.end();
    });
}

function parseGitHubSha256Digest(digest: string | undefined): string | undefined {
    const prefix = "sha256:";
    if (!digest?.startsWith(prefix)) {
        return undefined;
    }

    const value = digest.slice(prefix.length);
    return /^[a-fA-F0-9]{64}$/.test(value) ? value.toLowerCase() : undefined;
}

async function withDownloadLock<T>(storageDir: string, action: () => Promise<T>): Promise<T> {
    const lockPath = path.join(storageDir, lockFileName);
    const deadline = Date.now() + 120_000;
    let lockHandle: fs.promises.FileHandle | undefined;

    while (!lockHandle) {
        try {
            lockHandle = await fs.promises.open(lockPath, "wx");
        } catch (error) {
            if (!isFileExistsError(error) || Date.now() >= deadline) {
                throw error;
            }
            await delay(500);
        }
    }

    try {
        return await action();
    } finally {
        await lockHandle.close();
        await fs.promises.rm(lockPath, { force: true });
    }
}

function downloadFile(url: string, targetPath: string, redirectsLeft = 5): Promise<void> {
    return new Promise((resolve, reject) => {
        const request = https.get(
            url,
            {
                headers: {
                    "User-Agent": "Hexana-VSCode",
                },
            },
            (response) => {
                const statusCode = response.statusCode ?? 0;
                const location = response.headers.location;

                if (statusCode >= 300 && statusCode < 400 && location && redirectsLeft > 0) {
                    response.resume();
                    const nextUrl = new URL(location, url).toString();
                    downloadFile(nextUrl, targetPath, redirectsLeft - 1).then(resolve, reject);
                    return;
                }

                if (statusCode < 200 || statusCode >= 300) {
                    response.resume();
                    reject(new Error(`Download failed with HTTP ${statusCode}`));
                    return;
                }

                const file = fs.createWriteStream(targetPath, { flags: "w" });
                response.pipe(file);
                file.on("finish", () => {
                    file.close((error) => {
                        if (error) {
                            reject(error);
                        } else {
                            resolve();
                        }
                    });
                });
                file.on("error", reject);
            },
        );

        request.setTimeout(60_000, () => {
            request.destroy(new Error("Download timed out"));
        });
        request.on("error", reject);
        request.end();
    });
}

async function verifySha256(filePath: string, expectedSha256: string): Promise<void> {
    const actualSha256 = await sha256(filePath);
    if (actualSha256 !== expectedSha256) {
        throw new Error(`Downloaded MCP server checksum mismatch: expected ${expectedSha256}, got ${actualSha256}`);
    }
}

function sha256(filePath: string): Promise<string> {
    return new Promise((resolve, reject) => {
        const hash = crypto.createHash("sha256");
        const stream = fs.createReadStream(filePath);
        stream.on("data", (chunk) => hash.update(chunk));
        stream.on("end", () => resolve(hash.digest("hex")));
        stream.on("error", reject);
    });
}

function delay(milliseconds: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function isFileExistsError(error: unknown): boolean {
    return typeof error === "object" && error !== null && "code" in error && error.code === "EEXIST";
}

import * as crypto from "crypto";
import * as fs from "fs";
import * as http from "http";
import * as https from "https";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";

const githubReleaseApiBaseUrl = "https://api.github.com/repos/JetBrains/hexana/releases";
const githubReleasesPageUrl = "https://github.com/JetBrains/hexana/releases";
const githubReleaseApiBaseUrlOverrideEnv = "HEXANA_MCP_GITHUB_RELEASE_API_URL";
const githubReleasesPageUrlOverrideEnv = "HEXANA_MCP_RELEASES_PAGE_URL";
const storageDirOverrideEnv = "HEXANA_MCP_STORAGE_DIR";
const fallbackMcpServerVersion = "v0.1.0";
const fallbackMcpServerArchiveFileName = "server-0.1.0.zip";
const fallbackMcpServerDownloadUrl = `https://github.com/JetBrains/hexana/releases/download/${fallbackMcpServerVersion}/${fallbackMcpServerArchiveFileName}`;
const fallbackSha256 = "4d5110965bc2534831e3db4d56607304ed67d927ceb744aaf3d46062c5f3e962";

const storageDirName = "mcp";
const installedServerDirName = "server";
const versionMarkerFileName = "server.version";
const lockFileName = ".download.lock";
const downloadLockTimeoutMs = 120_000;

interface PrepareOptions {
    force?: boolean;
    showErrorNotification?: boolean;
}

interface HexanaMcpRelease {
    version: string;
    downloadUrl: string;
    sha256: string;
    source: "github" | "fallback";
}

interface HexanaMcpServerStatus {
    expectedVersion: string;
    downloaded: boolean;
    version?: string;
    serverUri?: vscode.Uri;
}

interface DownloadLockInfo {
    pid: number;
    createdAt: number;
}

export function registerHexanaMcpServerCommands(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.commands.registerCommand("hexana.mcp.updateServer", async () => {
            const previousStatus = await getHexanaMcpServerStatus(context);
            const serverUri = await prepareHexanaMcpServer(context);
            if (serverUri) {
                const status = await getHexanaMcpServerStatus(context);
                if (previousStatus.downloaded && status.downloaded && previousStatus.version === status.version) {
                    void vscode.window.showInformationMessage(
                        `Hexana MCP server is already up to date (${status.version}).`,
                    );
                } else {
                    void vscode.window.showInformationMessage(
                        `Hexana MCP server is updated to ${status.expectedVersion}.`,
                    );
                }
            }
        }),
        vscode.commands.registerCommand("hexana.mcp.reinstallServer", async () => {
            const serverUri = await prepareHexanaMcpServer(context, { force: true });
            if (serverUri) {
                const status = await getHexanaMcpServerStatus(context);
                void vscode.window.showInformationMessage(
                    `Hexana MCP server is reinstalled (${status.version ?? status.expectedVersion}).`,
                );
            }
        }),
        vscode.commands.registerCommand("hexana.mcp.showServerStatus", async () => {
            const status = await getHexanaMcpServerStatus(context);
            const message = formatStatusMessage(status);
            const actions = status.downloaded
                ? ["Check for Updates", "Reinstall Server", "Reveal Server"]
                : ["Update"];
            const action = await vscode.window.showInformationMessage(message, ...actions);

            if (action === "Update" || action === "Check for Updates") {
                void vscode.commands.executeCommand("hexana.mcp.updateServer");
            } else if (action === "Reinstall Server") {
                void vscode.commands.executeCommand("hexana.mcp.reinstallServer");
            } else if (action === "Reveal Server" && status.serverUri) {
                void vscode.commands.executeCommand("revealFileInOS", status.serverUri);
            }
        }),
    );
}

export async function prepareHexanaMcpServer(
    context: vscode.ExtensionContext,
    options: PrepareOptions = {},
): Promise<vscode.Uri | undefined> {
    try {
        return await ensureHexanaMcpServer(context, options);
    } catch (error) {
        console.warn("[Hexana] MCP server was not prepared", error);
        if (options.showErrorNotification !== false) {
            void vscode.window.showInformationMessage(
                "Hexana MCP server could not be downloaded automatically.",
                "Retry",
                "Open Download Page",
            ).then((action) => {
                if (action === "Retry") {
                    void prepareHexanaMcpServer(context, options);
                } else if (action === "Open Download Page") {
                    void vscode.env.openExternal(vscode.Uri.parse(getGitHubReleasesPageUrl()));
                }
            });
        }
        return undefined;
    }
}

export async function getDownloadedHexanaMcpServerVersion(context: vscode.ExtensionContext): Promise<string | undefined> {
    try {
        const storageDir = mcpStorageDir(context);
        const serverDir = path.join(storageDir, installedServerDirName);
        const markerPath = path.join(storageDir, versionMarkerFileName);
        const launcherPath = getHexanaMcpServerLauncherPath(context);
        const [serverStat, launcherStat, marker] = await Promise.all([
            fs.promises.stat(serverDir),
            fs.promises.stat(launcherPath),
            fs.promises.readFile(markerPath, "utf8"),
        ]);
        if (!serverStat.isDirectory() || !launcherStat.isFile()) {
            return undefined;
        }
        if (process.platform !== "win32") {
            await fs.promises.access(launcherPath, fs.constants.X_OK);
        }
        return marker.trim() || undefined;
    } catch {
        return undefined;
    }
}

export function getHexanaMcpServerDir(context: vscode.ExtensionContext): string {
    return path.join(mcpStorageDir(context), installedServerDirName);
}

export function getHexanaMcpServerLauncherPath(context: vscode.ExtensionContext): string {
    return path.join(
        getHexanaMcpServerDir(context),
        "bin",
        process.platform === "win32" ? "server.bat" : "server",
    );
}

async function getHexanaMcpServerStatus(context: vscode.ExtensionContext): Promise<HexanaMcpServerStatus> {
    const latestRelease = await getCompatibleReleaseOrFallback(context);
    const storageDir = mcpStorageDir(context);
    const serverDir = path.join(storageDir, installedServerDirName);
    const markerPath = path.join(storageDir, versionMarkerFileName);

    const installed = await getPreparedServerVersion(serverDir, markerPath);
    if (installed) {
        return {
            expectedVersion: latestRelease.source === "fallback" ? installed : latestRelease.version,
            downloaded: latestRelease.source === "fallback" || installed === latestRelease.version,
            version: installed,
            serverUri: vscode.Uri.file(serverDir),
        };
    }

    return {
        expectedVersion: latestRelease.version,
        downloaded: false,
    };
}

function formatStatusMessage(status: HexanaMcpServerStatus): string {
    if (status.downloaded) {
        return `Hexana MCP server: ${status.version}. Latest known version: ${status.expectedVersion}.`;
    }

    if (status.version) {
        return `Hexana MCP server is not ready. Installed version: ${status.version}. Latest known version: ${status.expectedVersion}.`;
    }

    return `Hexana MCP server is not downloaded. Latest known version: ${status.expectedVersion}.`;
}

async function ensureHexanaMcpServer(
    context: vscode.ExtensionContext,
    options: PrepareOptions,
): Promise<vscode.Uri> {
    const storageDir = mcpStorageDir(context);
    const serverDir = path.join(storageDir, installedServerDirName);
    const markerPath = path.join(storageDir, versionMarkerFileName);

    let latestRelease: HexanaMcpRelease;
    try {
        latestRelease = await getCompatibleReleaseOrFallback(context);
    } catch (error) {
        const installed = await getPreparedServerVersion(serverDir, markerPath);
        if (installed) {
            console.warn("[Hexana] Could not fetch MCP release metadata, keeping installed server", error);
            return vscode.Uri.file(serverDir);
        }
        throw error;
    }

    await fs.promises.mkdir(storageDir, { recursive: true });

    if (!options.force) {
        if (await isPrepared(serverDir, markerPath, latestRelease.version)) {
            return vscode.Uri.file(serverDir);
        }
        if (latestRelease.source === "fallback" && await getPreparedServerVersion(serverDir, markerPath)) {
            return vscode.Uri.file(serverDir);
        }
    }

    return withDownloadLock(storageDir, async () => {
        if (!options.force) {
            if (await isPrepared(serverDir, markerPath, latestRelease.version)) {
                return vscode.Uri.file(serverDir);
            }
            if (latestRelease.source === "fallback" && await getPreparedServerVersion(serverDir, markerPath)) {
                return vscode.Uri.file(serverDir);
            }
        }

        await vscode.window.withProgress(
            {
                location: vscode.ProgressLocation.Notification,
                title: "Getting Hexana MCP ready...",
                cancellable: false,
            },
            async (progress) => {
                const tempArchivePath = path.join(
                    os.tmpdir(),
                    `hexana-mcp-${process.pid}-${Date.now()}-${crypto.randomBytes(6).toString("hex")}.zip`,
                );
                const tempExtractDir = path.join(
                    os.tmpdir(),
                    `hexana-mcp-extract-${process.pid}-${Date.now()}-${crypto.randomBytes(6).toString("hex")}`,
                );

                try {
                    progress.report({ increment: 5, message: "Downloading..." });
                    await downloadFile(latestRelease.downloadUrl, tempArchivePath);

                    progress.report({ increment: 65, message: "Verifying integrity..." });
                    await verifySha256(tempArchivePath, latestRelease.sha256);

                    progress.report({ increment: 15, message: "Extracting..." });
                    await extractZipArchive(tempArchivePath, tempExtractDir);
                    const extractedServerDir = await findExtractedServerDir(tempExtractDir);

                    progress.report({ increment: 15 });
                    await installServerAtomically(storageDir, serverDir, markerPath, extractedServerDir, latestRelease.version);
                } finally {
                    await Promise.all([
                        fs.promises.rm(tempArchivePath, { force: true }),
                        fs.promises.rm(tempExtractDir, { recursive: true, force: true }),
                    ]);
                }
            },
        );

        return vscode.Uri.file(serverDir);
    });
}

async function installServerAtomically(
    storageDir: string,
    serverDir: string,
    markerPath: string,
    extractedServerDir: string,
    version: string,
): Promise<void> {
    await fs.promises.mkdir(storageDir, { recursive: true });

    const installId = `${process.pid}-${Date.now()}-${crypto.randomBytes(6).toString("hex")}`;
    const stagedServerDir = path.join(storageDir, `.server-staged-${installId}`);
    const backupServerDir = path.join(storageDir, `.server-backup-${installId}`);
    const stagedMarkerPath = path.join(storageDir, `.server-version-${installId}.tmp`);
    let backupCreated = false;
    let serverInstalled = false;

    try {
        await fs.promises.cp(extractedServerDir, stagedServerDir, { recursive: true });
        await ensureLauncherExecutable(stagedServerDir);
        await fs.promises.writeFile(stagedMarkerPath, `${version}\n`, "utf8");

        if (await pathExists(serverDir)) {
            await fs.promises.rename(serverDir, backupServerDir);
            backupCreated = true;
        }

        await fs.promises.rename(stagedServerDir, serverDir);
        serverInstalled = true;
        await fs.promises.rename(stagedMarkerPath, markerPath);
    } catch (error) {
        await fs.promises.rm(stagedServerDir, { recursive: true, force: true });
        await fs.promises.rm(stagedMarkerPath, { force: true });

        if (backupCreated) {
            await fs.promises.rm(serverDir, { recursive: true, force: true });
            await fs.promises.rename(backupServerDir, serverDir).catch((restoreError) => {
                console.warn("[Hexana] Could not restore previous MCP server after failed update", restoreError);
            });
        } else if (serverInstalled) {
            await fs.promises.rm(serverDir, { recursive: true, force: true });
        }

        throw error;
    }

    if (backupCreated) {
        await fs.promises.rm(backupServerDir, { recursive: true, force: true }).catch((error) => {
            console.warn("[Hexana] Could not remove previous MCP server backup after update", error);
        });
    }
}

async function pathExists(filePath: string): Promise<boolean> {
    return await fs.promises.stat(filePath).then(() => true, () => false);
}

async function isPrepared(serverDir: string, markerPath: string, expectedVersion: string): Promise<boolean> {
    return await getPreparedServerVersion(serverDir, markerPath) === expectedVersion;
}

async function getPreparedServerVersion(serverDir: string, markerPath: string): Promise<string | undefined> {
    try {
        const launcherPath = path.join(
            serverDir,
            "bin",
            process.platform === "win32" ? "server.bat" : "server",
        );
        const [serverStat, launcherStat, marker] = await Promise.all([
            fs.promises.stat(serverDir),
            fs.promises.stat(launcherPath),
            fs.promises.readFile(markerPath, "utf8"),
        ]);
        if (!serverStat.isDirectory() || !launcherStat.isFile()) {
            return undefined;
        }
        if (process.platform !== "win32") {
            await fs.promises.access(launcherPath, fs.constants.X_OK);
        }
        return marker.trim() || undefined;
    } catch {
        return undefined;
    }
}

async function getCompatibleReleaseOrFallback(context: vscode.ExtensionContext): Promise<HexanaMcpRelease> {
    const expectedTag = expectedServerTag(context);
    try {
        return await fetchReleaseByTag(expectedTag);
    } catch (error) {
        if (expectedTag !== fallbackMcpServerVersion) {
            throw new Error(
                `Could not fetch compatible Hexana MCP release ${expectedTag} and no fallback is bundled for this extension version: ${error instanceof Error ? error.message : String(error)}`,
            );
        }
        console.warn("[Hexana] Could not fetch MCP release metadata, using fallback", error);
        return {
            version: fallbackMcpServerVersion,
            downloadUrl: fallbackMcpServerDownloadUrl,
            sha256: fallbackSha256,
            source: "fallback",
        };
    }
}

function expectedServerTag(context: vscode.ExtensionContext): string {
    const version = String(context.extension.packageJSON.version ?? "").trim();
    if (!version) {
        return fallbackMcpServerVersion;
    }
    return version.startsWith("v") ? version : `v${version}`;
}

async function fetchReleaseByTag(tag: string): Promise<HexanaMcpRelease> {
    const release = await getJson<GitHubRelease>(getGitHubReleaseApiUrl(tag));
    const bareTag = tag.replace(/^v/, "");
    if (release.tag_name !== tag && release.tag_name !== bareTag) {
        throw new Error(`GitHub release tag ${release.tag_name} does not match the expected ${tag}`);
    }
    const asset = release.assets.find((candidate) => /^server-.+\.zip$/.test(candidate.name));

    if (!asset) {
        throw new Error("Latest GitHub release does not contain a server-*.zip asset");
    }

    const sha256 = parseGitHubSha256Digest(asset.digest);
    if (!sha256) {
        throw new Error(`GitHub release asset ${asset.name} does not provide a SHA-256 digest`);
    }

    return {
        version: release.tag_name,
        downloadUrl: asset.browser_download_url,
        sha256,
        source: "github",
    };
}

function getGitHubReleaseApiUrl(tag: string): string {
    const base = process.env[githubReleaseApiBaseUrlOverrideEnv] ?? githubReleaseApiBaseUrl;
    return `${base}/tags/${encodeURIComponent(tag)}`;
}

function getGitHubReleasesPageUrl(): string {
    return process.env[githubReleasesPageUrlOverrideEnv] ?? githubReleasesPageUrl;
}

function mcpStorageDir(context: vscode.ExtensionContext): string {
    return process.env[storageDirOverrideEnv] ?? path.join(context.globalStorageUri.fsPath, storageDirName);
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
        const request = getHttpClient(url).get(
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
    const deadline = Date.now() + downloadLockTimeoutMs;
    let lockHandle: fs.promises.FileHandle | undefined;

    while (!lockHandle) {
        try {
            await fs.promises.mkdir(storageDir, { recursive: true });
            lockHandle = await fs.promises.open(lockPath, "wx");
            await lockHandle.writeFile(JSON.stringify({
                pid: process.pid,
                createdAt: Date.now(),
            } satisfies DownloadLockInfo));
        } catch (error) {
            if (!isFileExistsError(error)) {
                throw error;
            }
            await removeStaleDownloadLock(lockPath);
            if (Date.now() >= deadline) {
                throw new Error(`Timed out waiting for Hexana MCP download lock: ${lockPath}`);
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

async function removeStaleDownloadLock(lockPath: string): Promise<void> {
    const stats = await fs.promises.stat(lockPath).catch(() => undefined);
    if (!stats) {
        return;
    }
    if (Date.now() - stats.mtimeMs < downloadLockTimeoutMs) {
        return;
    }

    const lockInfo = await readDownloadLockInfo(lockPath);
    if (lockInfo && isProcessRunning(lockInfo.pid)) {
        return;
    }

    await fs.promises.rm(lockPath, { force: true });
}

async function readDownloadLockInfo(lockPath: string): Promise<DownloadLockInfo | undefined> {
    try {
        const parsed = JSON.parse(await fs.promises.readFile(lockPath, "utf8")) as Partial<DownloadLockInfo>;
        if (Number.isInteger(parsed.pid) && typeof parsed.createdAt === "number") {
            return {
                pid: parsed.pid as number,
                createdAt: parsed.createdAt,
            };
        }
    } catch {
        return undefined;
    }
    return undefined;
}

function isProcessRunning(pid: number): boolean {
    try {
        process.kill(pid, 0);
        return true;
    } catch (error) {
        return (error as NodeJS.ErrnoException).code === "EPERM";
    }
}

async function extractZipArchive(archivePath: string, targetDir: string): Promise<void> {
    await fs.promises.mkdir(targetDir, { recursive: true });
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const AdmZip = require("adm-zip") as new (path: string) => {
        extractAllTo(targetPath: string, overwrite?: boolean): void;
    };
    new AdmZip(archivePath).extractAllTo(targetDir, true);
}

async function findExtractedServerDir(extractDir: string): Promise<string> {
    if (await hasServerLauncher(extractDir)) {
        return extractDir;
    }

    const entries = await fs.promises.readdir(extractDir, { withFileTypes: true });
    for (const entry of entries) {
        if (!entry.isDirectory()) {
            continue;
        }
        const candidate = path.join(extractDir, entry.name);
        if (await hasServerLauncher(candidate)) {
            return candidate;
        }
    }

    throw new Error("Downloaded MCP server archive does not contain bin/server.");
}

async function hasServerLauncher(serverDir: string): Promise<boolean> {
    const launcherPath = path.join(serverDir, "bin", "server");
    const stats = await fs.promises.stat(launcherPath).catch(() => undefined);
    return stats?.isFile() ?? false;
}

async function ensureLauncherExecutable(serverDir: string): Promise<void> {
    if (process.platform === "win32") {
        return;
    }
    const launcherPath = path.join(serverDir, "bin", "server");
    await fs.promises.chmod(launcherPath, 0o755);
}

function downloadFile(url: string, targetPath: string, redirectsLeft = 5): Promise<void> {
    return new Promise((resolve, reject) => {
        const request = getHttpClient(url).get(
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

function getHttpClient(url: string): typeof http | typeof https {
    const protocol = new URL(url).protocol;
    if (protocol === "http:") {
        return http;
    }
    if (protocol === "https:") {
        return https;
    }
    throw new Error(`Unsupported URL protocol: ${protocol}`);
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

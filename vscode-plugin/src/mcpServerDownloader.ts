import * as crypto from "crypto";
import * as fs from "fs";
import * as http from "http";
import * as https from "https";
import * as os from "os";
import * as path from "path";
import * as semver from "semver";
import * as vscode from "vscode";

const githubReleaseApiBaseUrl = "https://api.github.com/repos/JetBrains/hexana/releases";
const downloadsReleasesIndexUrl = "https://download.jetbrains.com/resources/hexana/mcp/releases.json";
const downloadsReleasesPageUrl = "https://download.jetbrains.com/resources/hexana/mcp";
const githubReleaseApiBaseUrlOverrideEnv = "HEXANA_MCP_GITHUB_RELEASE_API_URL";
const downloadsReleasesIndexUrlOverrideEnv = "HEXANA_MCP_DOWNLOADS_RELEASES_INDEX_URL";
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
const githubReleasesPageSize = 100;

interface PrepareOptions {
    force?: boolean;
    showErrorNotification?: boolean;
}

interface HexanaMcpRelease {
    version: string;
    downloadUrl: string;
    sha256: string;
    source: "downloads-native" | "github" | "fallback";
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

interface DownloadProgress {
    downloadedBytes: number;
    totalBytes?: number;
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
        const launcherPath = getInstalledLauncherPath(serverDir);
        if (!launcherPath) {
            return undefined;
        }
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
    return getInstalledLauncherPath(getHexanaMcpServerDir(context))
        ?? path.join(getHexanaMcpServerDir(context), "bin", jvmLauncherFileName());
}

async function getHexanaMcpServerStatus(context: vscode.ExtensionContext): Promise<HexanaMcpServerStatus> {
    const storageDir = mcpStorageDir(context);
    const serverDir = path.join(storageDir, installedServerDirName);
    const markerPath = path.join(storageDir, versionMarkerFileName);
    const installed = await getPreparedServerVersion(serverDir, markerPath);

    let latestRelease: HexanaMcpRelease | undefined;
    try {
        latestRelease = await getLatestReleaseOrFallback();
    } catch (error) {
        console.warn("[Hexana] Could not fetch MCP release metadata for status", error);
    }

    if (installed) {
        const expectedVersion = latestRelease && latestRelease.source !== "fallback"
            ? latestRelease.version
            : installed;
        const downloaded = !latestRelease
            || latestRelease.source === "fallback"
            || installed === latestRelease.version;
        return {
            expectedVersion,
            downloaded,
            version: installed,
            serverUri: vscode.Uri.file(serverDir),
        };
    }

    return {
        expectedVersion: latestRelease?.version ?? fallbackMcpServerVersion,
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
        latestRelease = await getLatestReleaseOrFallback();
    } catch (error) {
        const installed = await getPreparedServerVersion(serverDir, markerPath);
        if (installed) {
            console.warn("[Hexana] Could not fetch MCP release metadata, keeping installed server", error);
            return vscode.Uri.file(serverDir);
        }
        throw error;
    }

    await fs.promises.mkdir(storageDir, { recursive: true });

    if (await canReuseInstalledServer(serverDir, markerPath, latestRelease, options.force === true)) {
        return vscode.Uri.file(serverDir);
    }

    return withDownloadLock(storageDir, async () => {
        if (await canReuseInstalledServer(serverDir, markerPath, latestRelease, options.force === true)) {
            return vscode.Uri.file(serverDir);
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
                    const downloadIncrementBudget = 65;
                    let reportedDownloadIncrement = 0;
                    let lastDownloadProgress: DownloadProgress | undefined;
                    let lastDownloadMessageAtBytes = 0;

                    progress.report({ increment: 5, message: "Downloading..." });
                    await downloadFile(latestRelease.downloadUrl, tempArchivePath, (downloadProgress) => {
                        lastDownloadProgress = downloadProgress;
                        const message = formatDownloadProgress(downloadProgress);
                        const totalBytes = downloadProgress.totalBytes;

                        if (totalBytes !== undefined) {
                            const nextIncrement = Math.min(
                                downloadIncrementBudget,
                                (downloadProgress.downloadedBytes / totalBytes) * downloadIncrementBudget,
                            );
                            const increment = Math.max(0, nextIncrement - reportedDownloadIncrement);
                            if (increment >= 1 || downloadProgress.downloadedBytes >= totalBytes) {
                                progress.report({ increment, message });
                                reportedDownloadIncrement = nextIncrement;
                            }
                        } else if (downloadProgress.downloadedBytes - lastDownloadMessageAtBytes >= 1024 * 1024) {
                            progress.report({ message });
                            lastDownloadMessageAtBytes = downloadProgress.downloadedBytes;
                        }
                    });

                    if (reportedDownloadIncrement < downloadIncrementBudget) {
                        progress.report({
                            increment: downloadIncrementBudget - reportedDownloadIncrement,
                            message: formatDownloadComplete(lastDownloadProgress),
                        });
                    }

                    progress.report({ increment: 10, message: "Verifying integrity..." });
                    await verifySha256(tempArchivePath, latestRelease.sha256);

                    progress.report({ increment: 10, message: "Extracting..." });
                    await extractZipArchive(tempArchivePath, tempExtractDir);
                    const extractedServerDir = await findExtractedServerDir(tempExtractDir);

                    progress.report({ increment: 10 });
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

async function canReuseInstalledServer(
    serverDir: string,
    markerPath: string,
    release: HexanaMcpRelease,
    force: boolean,
): Promise<boolean> {
    const installed = await getPreparedServerVersion(serverDir, markerPath);
    if (!installed) {
        return false;
    }
    if (!force && installed === release.version) {
        return true;
    }
    if (release.source === "fallback" && installed !== release.version) {
        return true;
    }
    return false;
}

async function getPreparedServerVersion(serverDir: string, markerPath: string): Promise<string | undefined> {
    try {
        const launcherPath = getInstalledLauncherPath(serverDir);
        if (!launcherPath) {
            return undefined;
        }
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

async function getLatestReleaseOrFallback(): Promise<HexanaMcpRelease> {
    const useGitHubOverride = process.env[githubReleaseApiBaseUrlOverrideEnv] !== undefined;
    try {
        if (useGitHubOverride) {
            return await fetchLatestSemverGitHubRelease();
        }
        return await fetchLatestNativeDownloadsRelease();
    } catch (error) {
        console.warn(
            useGitHubOverride
                ? "[Hexana] Could not fetch latest GitHub MCP release metadata"
                : "[Hexana] Could not fetch latest native MCP release metadata",
            error,
        );
        if (!useGitHubOverride) {
            if (isNativeDownloadsFallbackError(error)) {
                console.warn("[Hexana] Using fallback MCP server release");
                return fallbackRelease();
            }
            throw error;
        }
        console.warn("[Hexana] Using fallback MCP server release");
        return fallbackRelease();
    }
}

async function fetchLatestNativeDownloadsRelease(): Promise<HexanaMcpRelease> {
    const platform = currentDownloadsPlatform();
    if (!platform) {
        throw new NativeDownloadsFallbackError(`Native MCP download is not published for ${process.platform}-${process.arch}`);
    }

    const index = await getJson<DownloadsReleasesIndex>(getDownloadsReleasesIndexUrl());
    if (index.schemaVersion !== 1) {
        throw new Error(`Unsupported Hexana MCP releases index schema version: ${index.schemaVersion}`);
    }

    const latestEntry = selectDownloadsReleaseEntry(index);
    if (!latestEntry) {
        throw new Error("Hexana MCP releases index does not contain a latest release entry");
    }

    const manifest = await getJson<DownloadsReleaseManifest>(latestEntry.manifestUrl);
    if (manifest.version !== latestEntry.version) {
        throw new Error(`Hexana MCP release manifest version mismatch: index=${latestEntry.version}, manifest=${manifest.version}`);
    }

    const artifact = manifest.artifacts.find((candidate) =>
        candidate.platform === platform && candidate.kind === "native-executable" && isSha256(candidate.sha256),
    );
    if (!artifact) {
        throw new NativeDownloadsFallbackError(`Hexana MCP release ${manifest.version} does not contain a native executable for ${platform}`);
    }

    const downloadUrl = artifact.downloadsUrl
        ?? urlJoin(manifest.downloadsPrefix, artifact.name)
        ?? urlJoin(index.downloadsRoot, `${manifest.version}/${artifact.name}`);
    if (!downloadUrl) {
        throw new Error(`Hexana MCP release ${manifest.version} native artifact has no download URL`);
    }

    return {
        version: manifest.version,
        downloadUrl,
        sha256: artifact.sha256.toLowerCase(),
        source: "downloads-native",
    };
}

function selectDownloadsReleaseEntry(index: DownloadsReleasesIndex): DownloadsReleaseIndexEntry | undefined {
    const latest = index.releases.find((release) => release.version === index.latest);
    if (latest) {
        return latest;
    }

    return index.releases
        .map((release) => {
            const version = semver.valid(release.version);
            return version ? { normalizedVersion: version, release } : undefined;
        })
        .filter((candidate): candidate is { normalizedVersion: string; release: DownloadsReleaseIndexEntry } => candidate !== undefined)
        .sort((left, right) => semver.rcompare(left.normalizedVersion, right.normalizedVersion))[0]?.release;
}

function currentDownloadsPlatform(): string | undefined {
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

function getDownloadsReleasesIndexUrl(): string {
    return process.env[downloadsReleasesIndexUrlOverrideEnv] ?? downloadsReleasesIndexUrl;
}

function fallbackRelease(): HexanaMcpRelease {
    return {
        version: fallbackMcpServerVersion,
        downloadUrl: fallbackMcpServerDownloadUrl,
        sha256: fallbackSha256,
        source: "fallback",
    };
}

class NativeDownloadsFallbackError extends Error {
}

function isNativeDownloadsFallbackError(error: unknown): boolean {
    return error instanceof NativeDownloadsFallbackError;
}

async function fetchLatestSemverGitHubRelease(): Promise<HexanaMcpRelease> {
    const candidates: Array<{ version: string; release: HexanaMcpRelease }> = [];
    let page = 1;
    while (true) {
        const releases = await getJson<GitHubRelease[]>(getGitHubReleasesApiUrl(page));
        for (const release of releases) {
            const version = semver.valid(release.tag_name);
            const mcpRelease = version ? toMcpRelease(release) : undefined;
            if (version && mcpRelease) {
                candidates.push({version, release: mcpRelease});
            }
        }
        if (releases.length < githubReleasesPageSize) {
            break;
        }
        page++;
    }

    const latest = candidates.sort((left, right) => semver.rcompare(left.version, right.version))[0]?.release;
    if (!latest) {
        throw new Error("GitHub releases pages do not contain a semver-tagged server-*.zip asset with a SHA-256 digest");
    }

    return latest;
}

function toMcpRelease(release: GitHubRelease): HexanaMcpRelease | undefined {
    const asset = release.assets
        .map((candidate) => ({
            asset: candidate,
            sha256: /^server-.+\.zip$/.test(candidate.name) ? parseGitHubSha256Digest(candidate.digest) : undefined,
        }))
        .find((candidate) => candidate.sha256 !== undefined);

    if (!asset?.sha256) {
        return undefined;
    }

    return {
        version: release.tag_name,
        downloadUrl: asset.asset.browser_download_url,
        sha256: asset.sha256,
        source: "github",
    };
}

function getGitHubReleasesApiUrl(page: number): string {
    const base = process.env[githubReleaseApiBaseUrlOverrideEnv] ?? githubReleaseApiBaseUrl;
    const separator = base.includes("?") ? "&" : "?";
    return `${base}${separator}per_page=${githubReleasesPageSize}&page=${page}`;
}

function getGitHubReleasesPageUrl(): string {
    return process.env[githubReleasesPageUrlOverrideEnv] ?? downloadsReleasesPageUrl;
}

function mcpStorageDir(context: vscode.ExtensionContext): string {
    return process.env[storageDirOverrideEnv] ?? path.join(context.globalStorageUri.fsPath, storageDirName);
}

interface DownloadsReleasesIndex {
    schemaVersion: number;
    latest: string;
    downloadsRoot: string;
    releases: DownloadsReleaseIndexEntry[];
}

interface DownloadsReleaseIndexEntry {
    version: string;
    manifestUrl: string;
}

interface DownloadsReleaseManifest {
    version: string;
    downloadsPrefix?: string;
    artifacts: DownloadsReleaseArtifact[];
}

interface DownloadsReleaseArtifact {
    name: string;
    platform: string;
    kind: string;
    sha256: string;
    downloadsUrl?: string | null;
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

function isSha256(value: string | undefined): boolean {
    return value !== undefined && /^[a-fA-F0-9]{64}$/.test(value);
}

function urlJoin(base: string | undefined, pathSegment: string): string | undefined {
    if (!base) {
        return undefined;
    }
    return `${base.replace(/\/+$/, "")}/${pathSegment.replace(/^\/+/, "")}`;
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

    throw new Error("Downloaded MCP server archive does not contain bin/hexana-mcp or bin/server.");
}

async function hasServerLauncher(serverDir: string): Promise<boolean> {
    const launchers = launcherCandidatePaths(serverDir);
    const launcherStats = await Promise.all(
        launchers.map((launcherPath) => fs.promises.stat(launcherPath).catch(() => undefined)),
    );
    return launcherStats.some((stats) => stats?.isFile() ?? false);
}

async function ensureLauncherExecutable(serverDir: string): Promise<void> {
    if (process.platform === "win32") {
        return;
    }
    await Promise.all(
        launcherCandidatePaths(serverDir).map(async (launcherPath) => {
            const stats = await fs.promises.stat(launcherPath).catch(() => undefined);
            if (stats?.isFile()) {
                await fs.promises.chmod(launcherPath, 0o755);
            }
        }),
    );
}

function getInstalledLauncherPath(serverDir: string): string | undefined {
    return launcherCandidatePaths(serverDir).find((launcherPath) => {
        try {
            return fs.statSync(launcherPath).isFile();
        } catch {
            return false;
        }
    });
}

function launcherCandidatePaths(serverDir: string): string[] {
    return [
        path.join(serverDir, "bin", nativeLauncherFileName()),
        path.join(serverDir, "bin", jvmLauncherFileName()),
    ];
}

function nativeLauncherFileName(): string {
    return process.platform === "win32" ? "hexana-mcp.exe" : "hexana-mcp";
}

function jvmLauncherFileName(): string {
    return process.platform === "win32" ? "server.bat" : "server";
}

function formatDownloadProgress(progress: DownloadProgress): string {
    const downloaded = formatMegabytes(progress.downloadedBytes);
    return progress.totalBytes !== undefined
        ? `Downloading ${downloaded} / ${formatMegabytes(progress.totalBytes)}...`
        : `Downloading ${downloaded}...`;
}

function formatDownloadComplete(progress: DownloadProgress | undefined): string {
    return progress
        ? `Downloaded ${formatMegabytes(progress.downloadedBytes)}.`
        : "Download complete.";
}

function formatMegabytes(bytes: number): string {
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function downloadFile(
    url: string,
    targetPath: string,
    onProgress?: (progress: DownloadProgress) => void,
    redirectsLeft = 5,
): Promise<void> {
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
                    downloadFile(nextUrl, targetPath, onProgress, redirectsLeft - 1).then(resolve, reject);
                    return;
                }

                if (statusCode < 200 || statusCode >= 300) {
                    response.resume();
                    reject(new Error(`Download failed with HTTP ${statusCode}`));
                    return;
                }

                const file = fs.createWriteStream(targetPath, { flags: "w" });
                const totalBytes = parseContentLength(response.headers["content-length"]);
                let downloadedBytes = 0;
                response.on("data", (chunk: Buffer) => {
                    downloadedBytes += chunk.length;
                    onProgress?.({
                        downloadedBytes,
                        totalBytes,
                    });
                });
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

function parseContentLength(value: string | string[] | undefined): number | undefined {
    const rawValue = Array.isArray(value) ? value[0] : value;
    if (rawValue === undefined) {
        return undefined;
    }

    const parsed = Number(rawValue);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
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

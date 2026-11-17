/**
 * Managed wasi-sdk installer for Hexana.
 *
 * Downloads wasi-sdk releases from GitHub into per-machine global storage.
 * The bundled lldb is offered strictly opt-in: after an install or activation
 * (and from the status command), an explicit "Use as LLDB path" action writes
 * the managed lldb path into the existing `hexana.lldbPath` setting. This
 * module does NOT change the lldb resolution mechanism; the setting is only
 * written on an explicit user click.
 *
 * Storage layout (root = <globalStorageUri>/wasi-sdk or HEXANA_WASI_SDK_STORAGE_DIR):
 *   <root>/wasi-sdk-<N>.0/     installed SDK directory (tar.gz top-level extracted here)
 *   <root>/manifest.json        {schemaVersion, activeVersion, installed:[...]}
 *   <root>/releases-cache.json  {fetchedAt, releases:[...]}
 *   <root>/.tmp/                staging area (cleaned up after each install)
 *   <root>/.download.lock       advisory lock file
 */
import * as crypto from "crypto";
import * as fs from "fs";
import * as https from "https";
import * as http from "http";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { execFile } from "child_process";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const GITHUB_API_URL = "https://api.github.com/repos/WebAssembly/wasi-sdk/releases?per_page=30";
const STORAGE_DIR_OVERRIDE_ENV = "HEXANA_WASI_SDK_STORAGE_DIR";
const STORAGE_DIR_NAME = "wasi-sdk";
const LOCK_FILE_NAME = ".download.lock";
const MANIFEST_FILE_NAME = "manifest.json";
const RELEASES_CACHE_FILE_NAME = "releases-cache.json";
const TMP_DIR_NAME = ".tmp";
const RELEASES_CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour
const LOCK_TIMEOUT_MS = 120_000;
const MANIFEST_SCHEMA_VERSION = 1;

/** Minimum wasi-sdk version that bundles lldb. */
const MIN_VERSION_WITH_LLDB = 31;

// ---------------------------------------------------------------------------
// Public interfaces
// ---------------------------------------------------------------------------

interface InstalledEntry {
    version: number;
    dirName: string;
    installedAt: string;
    sourceUrl: string;
    sha256: string | null;
}

interface Manifest {
    schemaVersion: number;
    activeVersion: number | null;
    installed: InstalledEntry[];
}

interface ReleasesCache {
    fetchedAt: string;
    releases: WasiRelease[];
}

interface WasiRelease {
    version: number;
    tag: string;
    assets: WasiAsset[];
}

interface WasiAsset {
    name: string;
    url: string;
    digest: string | null;
    size: number;
}

// ---------------------------------------------------------------------------
// Module-level state
// ---------------------------------------------------------------------------

/** Cached path to the managed lldb binary. Set during activation. */
let managedLldbPath: string | null = null;

/** Storage root — set once from the context passed to initWasiSdkManager(). */
let globalStorageRoot: string | null = null;

// ---------------------------------------------------------------------------
// Activation
// ---------------------------------------------------------------------------

/**
 * Called during extension activation. Reads the manifest and populates
 * `managedLldbPath` synchronously so `getManagedLldbPath()` works without I/O.
 */
export function initWasiSdkManager(context: vscode.ExtensionContext): void {
    globalStorageRoot = resolveStorageRoot(context);
    managedLldbPath = readActiveLldbPath(globalStorageRoot);
}

/**
 * Returns the path to the managed lldb binary for the active version,
 * or null if no managed version is installed/active.
 * Synchronous — reads from module-level cache populated during activation.
 *
 * This backs the explicit "Use as LLDB path" opt-in action only; it is
 * NOT consulted by the lldb resolution chain.
 */
export function getManagedLldbPath(): string | null {
    return managedLldbPath;
}

/** Button label for the explicit opt-in that writes `hexana.lldbPath`. */
const USE_AS_LLDB_PATH_ACTION = "Use as LLDB path";

/**
 * Writes the managed lldb path into the existing `hexana.lldbPath` setting
 * (global scope). Called only from an explicit user click on the
 * "Use as LLDB path" action, never automatically.
 */
function applyLldbPathSetting(lldbPath: string): Thenable<void> {
    return vscode.workspace.getConfiguration("hexana")
        .update("lldbPath", lldbPath, vscode.ConfigurationTarget.Global);
}

/**
 * Shows an information message with the "Use as LLDB path" action and
 * applies the setting only when the user clicks it.
 */
function offerUseAsLldbPath(message: string, lldbPath: string): void {
    void vscode.window.showInformationMessage(message, USE_AS_LLDB_PATH_ACTION).then((action) => {
        if (action === USE_AS_LLDB_PATH_ACTION) {
            void applyLldbPathSetting(lldbPath).then(() => {
                void vscode.window.showInformationMessage(
                    `hexana.lldbPath is now set to ${lldbPath}.`,
                );
            });
        }
    });
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

export function registerWasiSdkCommands(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.commands.registerCommand("hexana.wasiSdk.download", async () => {
            if (!globalStorageRoot) {
                globalStorageRoot = resolveStorageRoot(context);
            }
            await openVersionChooser(globalStorageRoot);
        }),
        vscode.commands.registerCommand("hexana.wasiSdk.status", async () => {
            if (!globalStorageRoot) {
                globalStorageRoot = resolveStorageRoot(context);
            }
            await showStatus(globalStorageRoot);
        }),
    );
}

// ---------------------------------------------------------------------------
// Storage root
// ---------------------------------------------------------------------------

function resolveStorageRoot(context: vscode.ExtensionContext): string {
    const envOverride = process.env[STORAGE_DIR_OVERRIDE_ENV];
    if (envOverride) {
        return envOverride;
    }
    return path.join(context.globalStorageUri.fsPath, STORAGE_DIR_NAME);
}

// ---------------------------------------------------------------------------
// Manifest
// ---------------------------------------------------------------------------

function readManifest(root: string): Manifest {
    const manifestPath = path.join(root, MANIFEST_FILE_NAME);
    try {
        const raw = JSON.parse(fs.readFileSync(manifestPath, "utf8")) as Partial<Manifest>;
        if (
            raw.schemaVersion === MANIFEST_SCHEMA_VERSION &&
            (raw.activeVersion === null || typeof raw.activeVersion === "number") &&
            Array.isArray(raw.installed)
        ) {
            return raw as Manifest;
        }
    } catch {
        // Missing or corrupt — fall through to rebuild
    }
    return rebuildManifestFromDisk(root);
}

function rebuildManifestFromDisk(root: string): Manifest {
    const installed: InstalledEntry[] = [];
    try {
        const entries = fs.readdirSync(root, { withFileTypes: true });
        for (const entry of entries) {
            if (!entry.isDirectory()) continue;
            const m = /^wasi-sdk-(\d+)\.0$/.exec(entry.name);
            if (!m) continue;
            const version = parseInt(m[1], 10);
            if (version < MIN_VERSION_WITH_LLDB) continue;
            const lldbBin = path.join(root, entry.name, lldbRelativePath());
            if (!fs.existsSync(lldbBin)) continue;
            installed.push({
                version,
                dirName: entry.name,
                installedAt: "unknown",
                sourceUrl: "",
                sha256: null,
            });
        }
    } catch {
        // Root doesn't exist yet
    }
    const activeVersion = installed.length > 0
        ? Math.max(...installed.map((e) => e.version))
        : null;
    return { schemaVersion: MANIFEST_SCHEMA_VERSION, activeVersion, installed };
}

function writeManifest(root: string, manifest: Manifest): void {
    fs.mkdirSync(root, { recursive: true });
    fs.writeFileSync(path.join(root, MANIFEST_FILE_NAME), JSON.stringify(manifest, null, 2), "utf8");
}

// ---------------------------------------------------------------------------
// Active lldb resolution
// ---------------------------------------------------------------------------

function readActiveLldbPath(root: string): string | null {
    try {
        const manifest = readManifest(root);
        if (manifest.activeVersion === null) return null;
        const entry = manifest.installed.find((e) => e.version === manifest.activeVersion);
        if (!entry) return null;
        const lldbBin = path.join(root, entry.dirName, lldbRelativePath());
        if (!fs.existsSync(lldbBin)) return null;
        return lldbBin;
    } catch {
        return null;
    }
}

// ---------------------------------------------------------------------------
// Platform helpers
// ---------------------------------------------------------------------------

function currentOs(): "macos" | "linux" | "windows" {
    switch (process.platform) {
        case "darwin": return "macos";
        case "win32": return "windows";
        default: return "linux";
    }
}

function currentArch(): "x86_64" | "arm64" {
    return process.arch === "arm64" ? "arm64" : "x86_64";
}

function lldbRelativePath(): string {
    return currentOs() === "windows" ? "bin/lldb.exe" : "bin/lldb";
}

function expectedAssetName(version: number): string {
    const arch = currentArch();
    const osStr = currentOs();
    return `wasi-sdk-${version}.0-${arch}-${osStr}.tar.gz`;
}

function installDirName(version: number): string {
    return `wasi-sdk-${version}.0`;
}

function archiveTopLevelDir(version: number): string {
    return `${expectedAssetName(version).replace(/\.tar\.gz$/, "")}`;
}

// ---------------------------------------------------------------------------
// Releases cache
// ---------------------------------------------------------------------------

function readReleasesCache(root: string): ReleasesCache | null {
    try {
        const raw = JSON.parse(
            fs.readFileSync(path.join(root, RELEASES_CACHE_FILE_NAME), "utf8"),
        ) as Partial<ReleasesCache>;
        if (raw.fetchedAt && Array.isArray(raw.releases)) {
            return raw as ReleasesCache;
        }
    } catch {
        // Missing or corrupt
    }
    return null;
}

function writeReleasesCache(root: string, cache: ReleasesCache): void {
    fs.mkdirSync(root, { recursive: true });
    fs.writeFileSync(
        path.join(root, RELEASES_CACHE_FILE_NAME),
        JSON.stringify(cache, null, 2),
        "utf8",
    );
}

/** Fetch and cache releases; falls back to stale cache on network/rate-limit errors. */
async function fetchReleases(root: string): Promise<{ releases: WasiRelease[]; stale: boolean }> {
    const cache = readReleasesCache(root);
    const now = Date.now();

    if (cache) {
        const age = now - new Date(cache.fetchedAt).getTime();
        if (age < RELEASES_CACHE_TTL_MS) {
            return { releases: cache.releases, stale: false };
        }
    }

    try {
        const githubReleases = await getJson<GitHubRelease[]>(GITHUB_API_URL);
        const releases = parseGitHubReleases(githubReleases);
        const newCache: ReleasesCache = { fetchedAt: new Date().toISOString(), releases };
        writeReleasesCache(root, newCache);
        return { releases, stale: false };
    } catch (error) {
        if (cache) {
            console.warn("[Hexana wasi-sdk] Could not fetch releases, using stale cache:", error);
            return { releases: cache.releases, stale: true };
        }
        throw error;
    }
}

interface GitHubRelease {
    tag_name: string;
    prerelease: boolean;
    assets: GitHubAsset[];
}

interface GitHubAsset {
    name: string;
    browser_download_url: string;
    digest?: string | null;
    size: number;
}

function parseGitHubReleases(raw: GitHubRelease[]): WasiRelease[] {
    const result: WasiRelease[] = [];
    for (const r of raw) {
        if (r.prerelease) continue;
        const version = parseTag(r.tag_name);
        if (version === null) continue;
        const assets: WasiAsset[] = r.assets.map((a) => ({
            name: a.name,
            url: a.browser_download_url,
            digest: a.digest ?? null,
            size: a.size,
        }));
        result.push({ version, tag: r.tag_name, assets });
    }
    return result.sort((a, b) => b.version - a.version);
}

function parseTag(tag: string): number | null {
    const m = /^wasi-sdk-(\d+)$/.exec(tag);
    if (!m) return null;
    const v = parseInt(m[1], 10);
    return v >= MIN_VERSION_WITH_LLDB ? v : null;
}

function parseSha256Digest(digest: string | null | undefined): string | null {
    if (!digest?.startsWith("sha256:")) return null;
    const hex = digest.slice("sha256:".length);
    return /^[a-fA-F0-9]{64}$/.test(hex) ? hex.toLowerCase() : null;
}

function selectAsset(release: WasiRelease): WasiAsset | null {
    const target = expectedAssetName(release.version);
    return release.assets.find((a) => a.name === target) ?? null;
}

// ---------------------------------------------------------------------------
// Version chooser (QuickPick)
// ---------------------------------------------------------------------------

async function openVersionChooser(root: string): Promise<void> {
    let releases: WasiRelease[];
    let stale: boolean;

    try {
        ({ releases, stale } = await vscode.window.withProgress(
            { location: vscode.ProgressLocation.Notification, title: "Fetching wasi-sdk releases..." },
            () => fetchReleases(root),
        ));
    } catch (error) {
        const msg = error instanceof Error ? error.message : String(error);
        void vscode.window.showErrorMessage(`Could not fetch wasi-sdk releases: ${msg}`);
        return;
    }

    if (releases.length === 0) {
        void vscode.window.showInformationMessage("No wasi-sdk releases found.");
        return;
    }

    const manifest = readManifest(root);
    const arch = currentArch();
    const osStr = currentOs();
    const staleNote = stale ? " (list as of cached date, network unavailable)" : "";

    const items: (vscode.QuickPickItem & { release: WasiRelease })[] = releases.map((r) => {
        const asset = selectAsset(r);
        const sizeMb = asset ? ` — ${(asset.size / 1024 / 1024).toFixed(0)} MB` : " — not available for this platform";
        const isInstalled = manifest.installed.some((e) => e.version === r.version);
        const isActive = manifest.activeVersion === r.version;
        const isLatest = r === releases[0];

        const badges = [
            isActive ? "active" : null,
            isInstalled && !isActive ? "installed" : null,
            isLatest ? "latest" : null,
        ].filter(Boolean).join(", ");

        const label = `wasi-sdk-${r.version}` + (badges ? ` [${badges}]` : "");
        return {
            label,
            description: `${arch}-${osStr}${sizeMb}${staleNote}`,
            release: r,
        };
    });

    const picked = await vscode.window.showQuickPick(items, {
        placeHolder: "Select a wasi-sdk version to install or activate",
        title: "Hexana: Download wasi-sdk",
    });

    if (!picked) return;

    const { release } = picked;
    const isInstalled = manifest.installed.some((e) => e.version === release.version);

    if (isInstalled && manifest.activeVersion !== release.version) {
        // Activate without downloading
        manifest.activeVersion = release.version;
        writeManifest(root, manifest);
        managedLldbPath = readActiveLldbPath(root);
        if (managedLldbPath) {
            offerUseAsLldbPath(
                `wasi-sdk-${release.version} is now the active managed version.`,
                managedLldbPath,
            );
        } else {
            void vscode.window.showInformationMessage(
                `wasi-sdk-${release.version} is now the active managed version.`,
            );
        }
        return;
    }

    if (isInstalled) {
        const lldbPath = getManagedLldbPath();
        if (lldbPath) {
            offerUseAsLldbPath(
                `wasi-sdk-${release.version} is already installed and active.`,
                lldbPath,
            );
        } else {
            void vscode.window.showInformationMessage(
                `wasi-sdk-${release.version} is already installed and active.`,
            );
        }
        return;
    }

    const asset = selectAsset(release);
    if (!asset) {
        void vscode.window.showErrorMessage(
            `No wasi-sdk-${release.version} asset found for ${arch}-${osStr}.`,
        );
        return;
    }

    await installVersion(root, release, asset);
}

// ---------------------------------------------------------------------------
// Status command
// ---------------------------------------------------------------------------

async function showStatus(root: string): Promise<void> {
    const manifest = readManifest(root);
    const activeLldbPath = readActiveLldbPath(root);

    let message: string;
    if (manifest.activeVersion !== null) {
        const installed = manifest.installed.map((e) => `wasi-sdk-${e.version}`).join(", ");
        message = `Active: wasi-sdk-${manifest.activeVersion}. Installed: ${installed}.`;
    } else {
        message = "No managed wasi-sdk installed.";
    }

    const actions = ["Check for Updates", "Choose Version", "Reveal"];
    if (activeLldbPath) {
        actions.push(USE_AS_LLDB_PATH_ACTION);
    }

    const action = await vscode.window.showInformationMessage(message, ...actions);

    if (action === "Check for Updates" || action === "Choose Version") {
        await vscode.commands.executeCommand("hexana.wasiSdk.download");
    } else if (action === "Reveal" && manifest.activeVersion !== null) {
        const entry = manifest.installed.find((e) => e.version === manifest.activeVersion);
        if (entry) {
            void vscode.commands.executeCommand(
                "revealFileInOS",
                vscode.Uri.file(path.join(root, entry.dirName)),
            );
        }
    } else if (action === USE_AS_LLDB_PATH_ACTION && activeLldbPath) {
        await applyLldbPathSetting(activeLldbPath);
        void vscode.window.showInformationMessage(
            `hexana.lldbPath is now set to ${activeLldbPath}.`,
        );
    }
}

// ---------------------------------------------------------------------------
// Installer
// ---------------------------------------------------------------------------

async function installVersion(root: string, release: WasiRelease, asset: WasiAsset): Promise<void> {
    // Preflight: check tar is available
    const tarAvailable = await checkTarAvailable();
    if (!tarAvailable) {
        void vscode.window.showErrorMessage(
            "Cannot install wasi-sdk: `tar` was not found. " +
            "Install tar and try again.",
        );
        return;
    }

    await withDownloadLock(root, async (token) => {
        await vscode.window.withProgress(
            {
                location: vscode.ProgressLocation.Notification,
                title: `Installing wasi-sdk-${release.version}...`,
                cancellable: true,
            },
            async (progress, cancellationToken) => {
                const tmpDir = path.join(root, TMP_DIR_NAME);
                const installId = `${process.pid}-${Date.now()}-${crypto.randomBytes(4).toString("hex")}`;
                const archivePath = path.join(tmpDir, `wasi-sdk-${release.version}-${installId}.tar.gz`);
                const extractDir = path.join(tmpDir, `extract-${installId}`);
                const finalDir = path.join(root, installDirName(release.version));

                const cleanup = async () => {
                    await Promise.all([
                        fs.promises.rm(archivePath, { force: true }),
                        fs.promises.rm(extractDir, { recursive: true, force: true }),
                    ]);
                };

                try {
                    fs.mkdirSync(tmpDir, { recursive: true });

                    if (cancellationToken.isCancellationRequested || token.isCancelled) return;

                    // Download
                    progress.report({ increment: 5, message: "Downloading..." });
                    let lastReportedIncrement = 0;
                    const downloadBudget = 70;
                    await downloadFile(asset.url, archivePath, (downloaded, total) => {
                        if (cancellationToken.isCancellationRequested || token.isCancelled) return;
                        if (total) {
                            const next = Math.min(downloadBudget, (downloaded / total) * downloadBudget);
                            const inc = Math.max(0, next - lastReportedIncrement);
                            if (inc >= 1 || downloaded >= total) {
                                const downloaded_mb = (downloaded / 1024 / 1024).toFixed(1);
                                const total_mb = (total / 1024 / 1024).toFixed(1);
                                progress.report({ increment: inc, message: `Downloading ${downloaded_mb} / ${total_mb} MB...` });
                                lastReportedIncrement = next;
                            }
                        } else {
                            const downloaded_mb = (downloaded / 1024 / 1024).toFixed(1);
                            progress.report({ message: `Downloading ${downloaded_mb} MB...` });
                        }
                    });

                    if (cancellationToken.isCancellationRequested || token.isCancelled) {
                        await cleanup();
                        return;
                    }

                    // Verify sha256
                    const expectedHash = parseSha256Digest(asset.digest);
                    if (expectedHash) {
                        progress.report({ increment: 5, message: "Verifying integrity..." });
                        const actualHash = await computeSha256(archivePath);
                        if (actualHash !== expectedHash) {
                            await cleanup();
                            void vscode.window.showErrorMessage(
                                `wasi-sdk-${release.version} checksum mismatch. ` +
                                `Expected ${expectedHash}, got ${actualHash}.`,
                            );
                            return;
                        }
                    } else {
                        progress.report({ increment: 5, message: "Skipping integrity check (no digest provided)..." });
                    }

                    if (cancellationToken.isCancellationRequested || token.isCancelled) {
                        await cleanup();
                        return;
                    }

                    // Extract
                    progress.report({ increment: 10, message: "Extracting..." });
                    fs.mkdirSync(extractDir, { recursive: true });
                    await spawnTar(archivePath, extractDir);

                    // Validate lldb is present in expected location
                    const topLevelDir = archiveTopLevelDir(release.version);
                    const extractedSdkDir = path.join(extractDir, topLevelDir);
                    const lldbBin = path.join(extractedSdkDir, lldbRelativePath());

                    if (!fs.existsSync(lldbBin)) {
                        await cleanup();
                        void vscode.window.showErrorMessage(
                            `wasi-sdk-${release.version} archive did not contain the expected lldb binary at ${lldbRelativePath()}.`,
                        );
                        return;
                    }

                    // Atomic install
                    progress.report({ increment: 8, message: "Installing..." });
                    const backupDir = path.join(root, `.backup-${installId}`);
                    let backupCreated = false;

                    try {
                        if (fs.existsSync(finalDir)) {
                            await fs.promises.rename(finalDir, backupDir);
                            backupCreated = true;
                        }
                        await fs.promises.rename(extractedSdkDir, finalDir);

                        // Update manifest
                        const manifest = readManifest(root);
                        const existing = manifest.installed.findIndex((e) => e.version === release.version);
                        const entry: InstalledEntry = {
                            version: release.version,
                            dirName: installDirName(release.version),
                            installedAt: new Date().toISOString(),
                            sourceUrl: asset.url,
                            sha256: expectedHash,
                        };
                        if (existing >= 0) {
                            manifest.installed[existing] = entry;
                        } else {
                            manifest.installed.push(entry);
                        }
                        manifest.activeVersion = release.version;
                        writeManifest(root, manifest);

                        // Update module-level cache with the FINAL location
                        // (the staging dir was renamed to finalDir above).
                        const lldbPathInstalled = path.join(finalDir, lldbRelativePath());
                        managedLldbPath = lldbPathInstalled;

                        if (backupCreated) {
                            await fs.promises.rm(backupDir, { recursive: true, force: true });
                        }

                        progress.report({ increment: 2, message: "Done." });
                        offerUseAsLldbPath(
                            `wasi-sdk-${release.version} installed successfully.`,
                            lldbPathInstalled,
                        );
                    } catch (err) {
                        await fs.promises.rm(finalDir, { recursive: true, force: true });
                        if (backupCreated) {
                            await fs.promises.rename(backupDir, finalDir).catch(() => undefined);
                        }
                        throw err;
                    }
                } catch (error) {
                    const msg = error instanceof Error ? error.message : String(error);
                    void vscode.window.showErrorMessage(
                        `Failed to install wasi-sdk-${release.version}: ${msg}`,
                    );
                } finally {
                    await cleanup();
                }
            },
        );
    });
}

// ---------------------------------------------------------------------------
// Lock
// ---------------------------------------------------------------------------

interface LockToken {
    isCancelled: boolean;
}

async function withDownloadLock<T>(root: string, action: (token: LockToken) => Promise<T>): Promise<T> {
    fs.mkdirSync(root, { recursive: true });
    const lockPath = path.join(root, LOCK_FILE_NAME);
    const deadline = Date.now() + LOCK_TIMEOUT_MS;
    const token: LockToken = { isCancelled: false };
    let handle: fs.promises.FileHandle | undefined;

    while (!handle) {
        try {
            handle = await fs.promises.open(lockPath, "wx");
            await handle.writeFile(JSON.stringify({ pid: process.pid, createdAt: Date.now() }));
        } catch (error) {
            const code = (error as NodeJS.ErrnoException).code;
            if (code !== "EEXIST") throw error;
            if (Date.now() >= deadline) throw new Error(`Timed out waiting for wasi-sdk download lock.`);
            await delay(500);
        }
    }

    try {
        return await action(token);
    } finally {
        token.isCancelled = true;
        await handle.close();
        await fs.promises.rm(lockPath, { force: true });
    }
}

// ---------------------------------------------------------------------------
// HTTP helpers (adapted from mcpServerDownloader.ts)
// ---------------------------------------------------------------------------

function getJson<T>(url: string): Promise<T> {
    return new Promise((resolve, reject) => {
        const client = new URL(url).protocol === "http:" ? http : https;
        const req = client.get(
            url,
            { headers: { "Accept": "application/vnd.github+json", "User-Agent": "Hexana-VSCode", "X-GitHub-Api-Version": "2022-11-28" } },
            (res) => {
                const code = res.statusCode ?? 0;
                const loc = res.headers.location;
                if (code >= 300 && code < 400 && loc) {
                    res.resume();
                    getJson<T>(new URL(loc, url).toString()).then(resolve, reject);
                    return;
                }
                const chunks: Buffer[] = [];
                res.on("data", (c: Buffer) => chunks.push(c));
                res.on("end", () => {
                    const body = Buffer.concat(chunks).toString("utf8");
                    if (code < 200 || code >= 300) {
                        reject(new Error(`GitHub releases request failed with HTTP ${code}: ${body.slice(0, 200)}`));
                        return;
                    }
                    try { resolve(JSON.parse(body) as T); } catch (e) { reject(e); }
                });
            },
        );
        req.setTimeout(30_000, () => req.destroy(new Error("GitHub releases request timed out")));
        req.on("error", reject);
        req.end();
    });
}

function downloadFile(
    url: string,
    targetPath: string,
    onProgress?: (downloaded: number, total: number | undefined) => void,
    redirectsLeft = 5,
): Promise<void> {
    return new Promise((resolve, reject) => {
        const client = new URL(url).protocol === "http:" ? http : https;
        const req = client.get(url, { headers: { "User-Agent": "Hexana-VSCode" } }, (res) => {
            const code = res.statusCode ?? 0;
            const loc = res.headers.location;
            if (code >= 300 && code < 400 && loc && redirectsLeft > 0) {
                res.resume();
                downloadFile(new URL(loc, url).toString(), targetPath, onProgress, redirectsLeft - 1).then(resolve, reject);
                return;
            }
            if (code < 200 || code >= 300) {
                res.resume();
                reject(new Error(`Download failed with HTTP ${code}`));
                return;
            }
            const total = parseContentLength(res.headers["content-length"]);
            let downloaded = 0;
            const file = fs.createWriteStream(targetPath, { flags: "w" });
            res.on("data", (chunk: Buffer) => {
                downloaded += chunk.length;
                onProgress?.(downloaded, total);
            });
            res.pipe(file);
            file.on("finish", () => file.close((e) => e ? reject(e) : resolve()));
            file.on("error", reject);
        });
        req.setTimeout(120_000, () => req.destroy(new Error("Download timed out")));
        req.on("error", reject);
        req.end();
    });
}

function parseContentLength(value: string | string[] | undefined): number | undefined {
    const raw = Array.isArray(value) ? value[0] : value;
    if (raw === undefined) return undefined;
    const n = Number(raw);
    return Number.isFinite(n) && n > 0 ? n : undefined;
}

function computeSha256(filePath: string): Promise<string> {
    return new Promise((resolve, reject) => {
        const hash = crypto.createHash("sha256");
        const stream = fs.createReadStream(filePath);
        stream.on("data", (c) => hash.update(c));
        stream.on("end", () => resolve(hash.digest("hex")));
        stream.on("error", reject);
    });
}

// ---------------------------------------------------------------------------
// tar extraction
// ---------------------------------------------------------------------------

function checkTarAvailable(): Promise<boolean> {
    return new Promise((resolve) => {
        const tarCmd = process.platform === "win32"
            ? "C:\\Windows\\System32\\tar.exe"
            : "tar";
        execFile(tarCmd, ["--version"], (error) => resolve(!error));
    });
}

function spawnTar(archivePath: string, extractDir: string): Promise<void> {
    return new Promise((resolve, reject) => {
        const tarCmd = process.platform === "win32"
            ? "C:\\Windows\\System32\\tar.exe"
            : "tar";
        execFile(tarCmd, ["-xzf", archivePath, "-C", extractDir], (error, _stdout, stderr) => {
            if (error) {
                reject(new Error(`tar extraction failed: ${stderr || error.message}`));
            } else {
                resolve();
            }
        });
    });
}

// ---------------------------------------------------------------------------
// Misc
// ---------------------------------------------------------------------------

function delay(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

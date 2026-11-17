/**
 * WebAssembly feature catalog for the runtime compatibility table.
 *
 * Keeps an ETag-validated copy of WebAssembly's `features.json` in global storage and hands
 * it to the webview. Fetching happens here, in the extension host, rather than in the
 * webview: the webview's CSP allowlists only `webview.cspSource`, `blob:` and
 * `https://api.osv.dev` for `connect-src`, so a webview-side fetch would be blocked — and
 * doing it host-side means no new network origin is exposed to webview content at all.
 *
 * Revalidation is a conditional GET rather than a HEAD probe: replaying the stored ETag as
 * `If-None-Match` costs one round-trip whether or not the file changed, where HEAD + GET
 * costs two when it did.
 *
 * Storage layout (root = <globalStorageUri>/wasm-features):
 *   <root>/features.json       the payload, verbatim
 *   <root>/features-meta.json  {etag, fetchedAt}
 */
import * as fs from "fs";
import * as http from "http";
import * as https from "https";
import * as path from "path";
import * as vscode from "vscode";

const FEATURES_URL = "https://raw.githubusercontent.com/WebAssembly/website/refs/heads/main/features.json";

/** Test overrides, following the convention in `mcpServerDownloader.ts`. */
const featuresUrlOverrideEnv = "HEXANA_WASM_FEATURES_URL";
const storageDirOverrideEnv = "HEXANA_WASM_FEATURES_STORAGE_DIR";

const STORAGE_DIR_NAME = "wasm-features";
const CATALOG_FILE_NAME = "features.json";
const METADATA_FILE_NAME = "features-meta.json";

/** Kept in step with FEATURE_CATALOG_TTL_MILLIS in plugins-shared. */
const CATALOG_TTL_MS = 24 * 60 * 60 * 1000;

const REQUEST_TIMEOUT_MS = 15_000;

/** Guards against a hostile or broken origin filling the disk. features.json is ~40 KB. */
const MAX_CATALOG_BYTES = 8 * 1024 * 1024;

interface CatalogMetadata {
    etag?: string;
    fetchedAt: number;
}

/**
 * Reads and refreshes the catalog. One instance per extension activation; the in-memory copy
 * is shared by every open editor so N webviews do not each hit the network.
 */
export class WasmFeatureCatalogProvider {
    private cachedJson: string | undefined;
    private refreshing: Promise<void> | undefined;

    /** Notifies open webviews when a refresh replaces the payload. */
    private readonly onDidChangeEmitter = new vscode.EventEmitter<string>();
    readonly onDidChange = this.onDidChangeEmitter.event;

    constructor(private readonly context: vscode.ExtensionContext) {}

    /**
     * The catalog to hand to a webview right now, never blocking on the network: the cache,
     * or the snapshot shipped in the VSIX, with a refresh kicked off in the background when
     * the cache is past its TTL.
     */
    get(): string {
        if (this.cachedJson === undefined) {
            this.cachedJson = this.readCache() ?? this.readBundled() ?? "";
        }
        void this.refreshIfStale();
        return this.cachedJson;
    }

    /** Refreshes regardless of TTL, and resolves once the attempt has finished. */
    async refreshNow(): Promise<void> {
        await this.refresh();
    }

    /**
     * Resolves once any refresh started by [get] has finished. Test seam: production callers
     * never need to wait, since `get` is deliberately non-blocking.
     */
    async settle(): Promise<void> {
        await this.refreshing;
    }

    dispose(): void {
        this.onDidChangeEmitter.dispose();
    }

    // -----------------------------------------------------------------------
    // Cache
    // -----------------------------------------------------------------------

    private storageRoot(): string {
        const override = process.env[storageDirOverrideEnv];
        if (override && override.trim().length > 0) {
            return override;
        }
        return path.join(this.context.globalStorageUri.fsPath, STORAGE_DIR_NAME);
    }

    private readCache(): string | undefined {
        try {
            const json = fs.readFileSync(path.join(this.storageRoot(), CATALOG_FILE_NAME), "utf8");
            return json.trim().length > 0 ? json : undefined;
        } catch {
            return undefined;
        }
    }

    private readMetadata(): CatalogMetadata | undefined {
        try {
            const raw = fs.readFileSync(path.join(this.storageRoot(), METADATA_FILE_NAME), "utf8");
            const parsed = JSON.parse(raw) as CatalogMetadata;
            return typeof parsed?.fetchedAt === "number" ? parsed : undefined;
        } catch {
            return undefined;
        }
    }

    private writeCache(json: string, etag: string | undefined, fetchedAt: number): void {
        const root = this.storageRoot();
        fs.mkdirSync(root, { recursive: true });
        writeAtomically(path.join(root, CATALOG_FILE_NAME), json);
        writeAtomically(path.join(root, METADATA_FILE_NAME), JSON.stringify({ etag, fetchedAt }));
    }

    /** A 304 confirms the bytes we hold; only their freshness moves. */
    private touchCache(fetchedAt: number): void {
        const root = this.storageRoot();
        const existing = this.readMetadata();
        fs.mkdirSync(root, { recursive: true });
        writeAtomically(
            path.join(root, METADATA_FILE_NAME),
            JSON.stringify({ etag: existing?.etag, fetchedAt }),
        );
    }

    /** The snapshot in the VSIX, synced from `plugins-shared/features/features.json`. */
    private readBundled(): string | undefined {
        try {
            return fs.readFileSync(
                path.join(this.context.extensionUri.fsPath, STORAGE_DIR_NAME, CATALOG_FILE_NAME),
                "utf8",
            );
        } catch {
            return undefined;
        }
    }

    // -----------------------------------------------------------------------
    // Refresh
    // -----------------------------------------------------------------------

    private async refreshIfStale(): Promise<void> {
        const metadata = this.readMetadata();
        const cached = this.readCache();
        if (cached !== undefined && metadata !== undefined) {
            const age = Date.now() - metadata.fetchedAt;
            // A clock that has moved backwards must not pin the cache as fresh forever.
            if (age >= 0 && age < CATALOG_TTL_MS) {
                return;
            }
        }
        await this.refresh();
    }

    private refresh(): Promise<void> {
        // Collapse concurrent callers (several editors opening at once) onto one request.
        if (this.refreshing) {
            return this.refreshing;
        }
        this.refreshing = this.doRefresh().finally(() => {
            this.refreshing = undefined;
        });
        return this.refreshing;
    }

    private async doRefresh(): Promise<void> {
        const etag = this.readMetadata()?.etag;
        const now = Date.now();
        try {
            const response = await fetchCatalog(etag);
            if (response.notModified) {
                this.touchCache(now);
                return;
            }
            // A truncated or non-JSON download must never replace a working catalog.
            if (!isParseableCatalog(response.body)) {
                return;
            }
            this.writeCache(response.body, response.etag, now);
            this.cachedJson = response.body;
            this.onDidChangeEmitter.fire(response.body);
        } catch {
            // A stale catalog degrades the table, it does not break it: the snapshot in the
            // VSIX is always available, so a failed refresh is silent.
        }
    }
}

/**
 * The same top-level shape `WasmFeatureCatalog.parse` in plugins-shared insists on, so the
 * host never caches a payload the webview would then reject and fall back from.
 */
function isParseableCatalog(body: string): boolean {
    try {
        const parsed = JSON.parse(body) as { features?: unknown; browsers?: unknown };
        return isJsonObject(parsed?.features) && isJsonObject(parsed?.browsers);
    } catch {
        return false;
    }
}

/** `typeof` reports both `null` and an array as `"object"`, and neither is a catalog. */
function isJsonObject(value: unknown): boolean {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function writeAtomically(target: string, text: string): void {
    const temp = `${target}.tmp`;
    fs.writeFileSync(temp, text, "utf8");
    fs.renameSync(temp, target);
}

interface CatalogResponse {
    notModified: boolean;
    body: string;
    etag?: string;
}

function featuresUrl(): string {
    const override = process.env[featuresUrlOverrideEnv];
    return override && override.trim().length > 0 ? override : FEATURES_URL;
}

function fetchCatalog(etag: string | undefined): Promise<CatalogResponse> {
    return new Promise((resolve, reject) => {
        const headers: Record<string, string> = { Accept: "application/json" };
        if (etag) {
            headers["If-None-Match"] = etag;
        }
        const url = featuresUrl();
        const client = new URL(url).protocol === "http:" ? http : https;
        const request = client.get(url, { headers, timeout: REQUEST_TIMEOUT_MS }, (response) => {
            if (response.statusCode === 304) {
                response.resume();
                resolve({ notModified: true, body: "" });
                return;
            }
            if (response.statusCode !== 200) {
                response.resume();
                reject(new Error(`Unexpected status ${response.statusCode}`));
                return;
            }

            const chunks: Buffer[] = [];
            let total = 0;
            response.on("data", (chunk: Buffer) => {
                total += chunk.length;
                if (total > MAX_CATALOG_BYTES) {
                    request.destroy();
                    reject(new Error("features.json exceeded the size limit"));
                    return;
                }
                chunks.push(chunk);
            });
            response.on("end", () => {
                resolve({
                    notModified: false,
                    body: Buffer.concat(chunks).toString("utf8"),
                    etag: firstHeaderValue(response.headers.etag),
                });
            });
            response.on("error", reject);
        });
        request.on("timeout", () => request.destroy(new Error("features.json request timed out")));
        request.on("error", reject);
    });
}

function firstHeaderValue(value: string | string[] | undefined): string | undefined {
    if (Array.isArray(value)) {
        return value[0];
    }
    return value;
}

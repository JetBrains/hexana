import * as path from "path";
import * as zlib from "zlib";
import * as vscode from "vscode";
import { getWebviewContent } from "./webviewContent";
import { extensionLib, getSelectedRuntime, runExport } from "./WasmRunConfigurations";
import { isNativeBinary } from "./NativeBinaryFileTypeDetector";
import { WasmFeatureCatalogProvider } from "./wasmFeatureCatalog";

/**
 * In-memory store for nested module byte arrays.
 *
 * When the webview asks to open a nested module we store the raw bytes here
 * keyed by a synthetic `hexana-module:` URI, then ask VS Code to open that
 * URI with our custom editor.  `openCustomDocument` checks this map first
 * so it can serve the data without touching the file system.
 */
const inMemoryModules = new Map<string, { data: Uint8Array; fileName: string }>();

/**
 * Type-definition sidecar written next to a script so the editor resolves the
 * scripting globals. Shipped with the extension and copied into the script's
 * folder; its content is the curated reference, not a hand-written stub.
 */
const HEXANA_SCRIPT_TYPES_FILE = "hexana-scripting.d.ts";

/**
 * Config sidecar that puts {@link HEXANA_SCRIPT_TYPES_FILE} in scope for a
 * standalone `.js` file, which otherwise has no association with a sibling
 * `.d.ts`. Its content comes from the webview so the JSON is defined once,
 * alongside the rest of the scripting API contract.
 */
const HEXANA_SCRIPT_JSCONFIG_FILE = "jsconfig.json";

/**
 * Project-relative directory Hexana keeps shared, editor-independent state in, and the file
 * inside it that holds the target runtimes for the compatibility table.
 *
 * Mirrors `HEXANA_PROJECT_DIR` / `RUNTIME_TARGETS_FILE` in
 * `plugins-shared/src/commonMain/kotlin/org/jetbrains/hexana/shared/features/RuntimeTargets.kt`,
 * which is the source of truth for the format. The IntelliJ plugin reads and writes the same
 * file, so a selection made in either editor is honoured by the other.
 *
 * Project-relative, so a multi-root workspace has one per folder; see [runtimeTargetsFolder].
 */
const HEXANA_PROJECT_DIR = ".hexana";
const RUNTIME_TARGETS_FILE = "runtimes.json";

/**
 * Generated JSON Schema written next to [RUNTIME_TARGETS_FILE], which references it. It is
 * what makes the settings file hand-editable: with it present, VS Code completes the keys
 * and the runtime names and flags a typo. The webview generates the content, since the
 * runtime names come from the feature catalog it already holds.
 */
const RUNTIME_TARGETS_SCHEMA_FILE = "runtimes.schema.json";

/**
 * The workspace folder whose `.hexana/runtimes.json` governs the binary at [scope].
 *
 * A multi-root workspace holds one settings file per folder, so the folder the *file* lives
 * in decides, not the first one in the list. A nested module (`hexana-module:`) belongs to
 * whatever file it was extracted from, so the chain is walked back to that file first.
 *
 * Anything still outside every folder - a file opened on its own, a module whose parent
 * cannot be resolved - falls back to the first folder, which is also the only folder in the
 * single-root case this used to assume.
 */
function runtimeTargetsFolder(scope?: vscode.Uri): vscode.WorkspaceFolder | undefined {
    const owning = scope ? vscode.workspace.getWorkspaceFolder(originatingFile(scope)) : undefined;
    return owning ?? vscode.workspace.workspaceFolders?.[0];
}

/** Follows a nested module's `parent` query back to the file on disk it came from. */
function originatingFile(uri: vscode.Uri): vscode.Uri {
    let current = uri;
    // Bounded rather than open: a module can be nested twice (a component inside an archive),
    // but a hand-crafted or corrupted query must not spin here.
    for (let depth = 0; depth < 8 && current.scheme === "hexana-module"; depth++) {
        const parent = new URLSearchParams(current.query).get("parent");
        if (!parent) {
            break;
        }
        try {
            current = vscode.Uri.parse(decodeURIComponent(parent));
        } catch {
            break;
        }
    }
    return current;
}

/**
 * Reads `.hexana/runtimes.json` from the workspace folder that governs [scope].
 *
 * Returns an empty string when there is no workspace, no file, or the file cannot be read.
 * The webview treats that as "nothing declared" and the compatibility table simply has
 * nothing to check against - an unreadable settings file is not worth an error popup on
 * every file open.
 */
async function readRuntimeTargetsJson(scope?: vscode.Uri): Promise<string> {
    const folder = runtimeTargetsFolder(scope);
    if (!folder) {
        return "";
    }
    const file = vscode.Uri.joinPath(folder.uri, HEXANA_PROJECT_DIR, RUNTIME_TARGETS_FILE);
    try {
        return new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(file));
    } catch {
        return "";
    }
}

/**
 * In-memory store for pending diff payloads keyed by a synthetic
 * `hexana-module:` URI.  When the user invokes "Compare WASM With…" we
 * store both byte arrays here and open a new custom editor tab whose URI
 * matches the key.  `resolveCustomEditor` checks this map and, when a
 * diff entry is present, sends a `load-diff` message instead of the
 * normal `load-wasm`.
 */
const pendingDiffs = new Map<string, {
    leftData: Uint8Array; leftName: string;
    rightData: Uint8Array; rightName: string;
}>();

/**
 * In-memory store for WAT text keyed by a synthetic `hexana-wat:` URI.
 *
 * When the webview produces WAT output for a wasm binary, it sends the text
 * to the extension host which stores it here and opens a native VS Code
 * editor tab backed by a `TextDocumentContentProvider`.
 */
const watDocuments = new Map<string, string>();

/**
 * Content provider for virtual `hexana-wat:` documents.
 *
 * VS Code's Monaco editor uses this to display WAT text with full editor
 * features (syntax highlighting, search, folding, etc.).
 */
class WatContentProvider implements vscode.TextDocumentContentProvider {
    private _onDidChange = new vscode.EventEmitter<vscode.Uri>();
    readonly onDidChange = this._onDidChange.event;

    provideTextDocumentContent(uri: vscode.Uri): string {
        return watDocuments.get(uri.toString()) ?? "";
    }

    update(uri: vscode.Uri): void {
        this._onDidChange.fire(uri);
    }
}

const watContentProvider = new WatContentProvider();

/**
 * Minimal read-only file-system provider for the `hexana-module` URI scheme.
 *
 * VS Code requires a registered `FileSystemProvider` before `vscode.openWith`
 * can open URIs with a custom scheme.  This provider serves bytes that were
 * previously stored in `inMemoryModules`.
 */
class HexanaModuleFS implements vscode.FileSystemProvider {
    private _onDidChangeFile = new vscode.EventEmitter<vscode.FileChangeEvent[]>();
    readonly onDidChangeFile = this._onDidChangeFile.event;

    watch(): vscode.Disposable {
        return new vscode.Disposable(() => {});
    }

    stat(uri: vscode.Uri): vscode.FileStat {
        const entry = inMemoryModules.get(uri.toString());
        if (entry) {
            return {
                type: vscode.FileType.File,
                ctime: 0,
                mtime: 0,
                size: entry.data.byteLength,
            };
        }
        // Diff or nested-module URIs whose in-memory data was lost after
        // a VS Code restart — return an empty placeholder so the editor
        // can open and show a friendly "session expired" message.
        if (uri.scheme === "hexana-module") {
            return {
                type: vscode.FileType.File,
                ctime: 0,
                mtime: 0,
                size: 0,
            };
        }
        throw vscode.FileSystemError.FileNotFound(uri);
    }

    readFile(uri: vscode.Uri): Uint8Array {
        const entry = inMemoryModules.get(uri.toString());
        if (entry) {
            return entry.data;
        }
        // Return empty bytes for expired in-memory URIs so the editor
        // can open and display a recovery message.
        if (uri.scheme === "hexana-module") {
            return new Uint8Array(0);
        }
        throw vscode.FileSystemError.FileNotFound(uri);
    }

    readDirectory(): [string, vscode.FileType][] {
        return [];
    }

    createDirectory(): void {}
    writeFile(): void {
        throw vscode.FileSystemError.NoPermissions("read-only");
    }
    delete(): void {
        throw vscode.FileSystemError.NoPermissions("read-only");
    }
    rename(): void {
        throw vscode.FileSystemError.NoPermissions("read-only");
    }
}

/**
 * Read-only custom editor provider for `.wasm` files.
 *
 * The provider reads the binary document, creates a webview that hosts the
 * Compose-for-Web viewer, and posts the raw bytes into the webview so that the
 * Kotlin/JS code can parse and render them.
 */
export class WasmEditorProvider implements vscode.CustomReadonlyEditorProvider<WasmDocument> {

    private static readonly viewType = "hexana.wasmEditor";

    private static readonly nativeExtensions = new Set([
        "o", "obj", "so", "dylib", "dll", "exe", "elf", "a", "lib",
        "ko", "sys", "axf", "bin", "out",
    ]);

    /**
     * Returns true when the URI looks like a native binary: either it has a
     * known native extension, or the file name has no extension at all
     * (extensionless files are common for native executables on Unix).
     */
    private static isNativeBinary(uri: vscode.Uri): boolean {
        const base = path.basename(uri.fsPath);
        if (!base.includes(".")) return true; // extensionless → treat as native
        const ext = base.split(".").pop()?.toLowerCase() ?? "";
        return WasmEditorProvider.nativeExtensions.has(ext);
    }

    /**
     * Registers `hexana.configureWasmRuntimes`, which opens the target-runtimes dialog in
     * the active Hexana editor.
     *
     * The dialog lives inside the editor webview rather than in VS Code settings because the
     * selection is written to the project's `.hexana/runtimes.json`, so the command needs a
     * webview to talk to.
     */
    public static registerConfigureRuntimesCommand(provider: WasmEditorProvider): vscode.Disposable {
        return vscode.commands.registerCommand("hexana.configureWasmRuntimes", () => {
            if (!provider.openWebviews.size) {
                void vscode.window.showInformationMessage(
                    "Open a Wasm or binary file in Hexana first: target runtimes are configured from its editor.",
                );
                return;
            }
            provider.broadcast({ type: "open-runtime-targets-dialog" });
        });
    }

    /**
     * Registers the `hexana.compareBinary` command that lets the user pick two
     * native binary files and opens a diff view showing per-section size differences.
     */
    public static registerCompareBinaryCommand(context: vscode.ExtensionContext): vscode.Disposable {
        return vscode.commands.registerCommand("hexana.compareBinary", async (contextUri?: vscode.Uri, selectedUris?: vscode.Uri[]) => {
            const isNative = WasmEditorProvider.isNativeBinary;
            const nativeFilter = { "Native Binary": ["o", "obj", "so", "dylib", "dll", "exe", "elf", "a", "lib", "ko", "sys", "axf", "bin", "out", "*"] };

            // When exactly two files are selected and both look like native binaries
            // (known extension or extensionless), compare them directly.
            if (selectedUris && selectedUris.length === 2 && selectedUris.every(u => isNative(u))) {
                const left = selectedUris[0];
                const right = selectedUris[1];

                const leftData = await vscode.workspace.fs.readFile(left);
                const rightData = await vscode.workspace.fs.readFile(right);
                const leftName = path.basename(left.fsPath);
                const rightName = path.basename(right.fsPath);

                const diffUri = vscode.Uri.parse(
                    `hexana-module:/${leftName} ↔ ${rightName}?diff=true&native=true&left=${encodeURIComponent(left.toString())}&right=${encodeURIComponent(right.toString())}`,
                );
                const key = diffUri.toString();

                inMemoryModules.set(key, { data: leftData, fileName: `${leftName} ↔ ${rightName}` });
                pendingDiffs.set(key, { leftData, leftName, rightData, rightName });

                try {
                    await vscode.commands.executeCommand(
                        "vscode.openWith",
                        diffUri,
                        WasmEditorProvider.viewType,
                    );
                } catch (err) {
                    console.error("[Hexana] Failed to open native diff view:", err);
                    vscode.window.showErrorMessage(`Failed to open diff view: ${err}`);
                }
                return;
            }

            // Left file: use the context-menu URI if available, otherwise prompt.
            const left = contextUri && !contextUri.fsPath.endsWith("/") && isNative(contextUri)
                ? contextUri
                : await vscode.window.showOpenDialog({
                    canSelectMany: false,
                    filters: nativeFilter,
                    title: "Compare Binary — Select First File",
                }).then(uris => uris?.[0]);
            if (!left) return;

            // Right file: always prompt.
            const right = await vscode.window.showOpenDialog({
                canSelectMany: false,
                filters: nativeFilter,
                title: `Compare With Binary File (against ${path.basename(left.fsPath)})`,
                defaultUri: vscode.Uri.file(path.dirname(left.fsPath)),
            }).then(uris => uris?.[0]);
            if (!right) return;

            const leftData = await vscode.workspace.fs.readFile(left);
            const rightData = await vscode.workspace.fs.readFile(right);
            const leftName = path.basename(left.fsPath);
            const rightName = path.basename(right.fsPath);

            const diffUri = vscode.Uri.parse(
                `hexana-module:/${leftName} ↔ ${rightName}?diff=true&native=true&left=${encodeURIComponent(left.toString())}&right=${encodeURIComponent(right.toString())}`,
            );
            const key = diffUri.toString();

            inMemoryModules.set(key, { data: leftData, fileName: `${leftName} ↔ ${rightName}` });
            pendingDiffs.set(key, { leftData, leftName, rightData, rightName });

            try {
                await vscode.commands.executeCommand(
                    "vscode.openWith",
                    diffUri,
                    WasmEditorProvider.viewType,
                );
            } catch (err) {
                console.error("[Hexana] Failed to open native diff view:", err);
                vscode.window.showErrorMessage(`Failed to open diff view: ${err}`);
            }
        });
    }

    /**
     * Registers the `hexana.compareWasm` command that lets the user pick two
     * `.wasm` files and opens a diff view in a new custom editor tab.
     */
    public static registerCompareCommand(context: vscode.ExtensionContext): vscode.Disposable {
        return vscode.commands.registerCommand("hexana.compareWasm", async (contextUri?: vscode.Uri, selectedUris?: vscode.Uri[]) => {
            const wasmFilter = { "WebAssembly": ["wasm"] };
            const isWasm = (uri: vscode.Uri) => uri.fsPath.endsWith(".wasm");

            // When exactly two .wasm files are selected in the explorer, use them directly.
            if (selectedUris && selectedUris.length === 2 && selectedUris.every(isWasm)) {
                const left = selectedUris[0];
                const right = selectedUris[1];

                const leftData = await vscode.workspace.fs.readFile(left);
                const rightData = await vscode.workspace.fs.readFile(right);
                const leftName = path.basename(left.fsPath);
                const rightName = path.basename(right.fsPath);

                const diffUri = vscode.Uri.parse(
                    `hexana-module:/${leftName} ↔ ${rightName}?diff=true&left=${encodeURIComponent(left.toString())}&right=${encodeURIComponent(right.toString())}`,
                );
                const key = diffUri.toString();

                inMemoryModules.set(key, { data: leftData, fileName: `${leftName} ↔ ${rightName}` });
                pendingDiffs.set(key, { leftData, leftName, rightData, rightName });

                try {
                    await vscode.commands.executeCommand(
                        "vscode.openWith",
                        diffUri,
                        WasmEditorProvider.viewType,
                    );
                } catch (err) {
                    console.error("[Hexana] Failed to open diff view:", err);
                    vscode.window.showErrorMessage(`Failed to open diff view: ${err}`);
                }
                return;
            }

            // Left file: use the context-menu URI if available, otherwise prompt.
            const left = contextUri && !contextUri.fsPath.endsWith("/")
                ? contextUri
                : await vscode.window.showOpenDialog({
                    canSelectMany: false,
                    filters: wasmFilter,
                    title: "Compare WASM — Select First Module",
                }).then(uris => uris?.[0]);
            if (!left) return;

            // Right file: always prompt.
            const right = await vscode.window.showOpenDialog({
                canSelectMany: false,
                filters: wasmFilter,
                title: `Compare With WASM File (against ${path.basename(left.fsPath)})`,
                defaultUri: vscode.Uri.file(path.dirname(left.fsPath)),
            }).then(uris => uris?.[0]);
            if (!right) return;

            const leftData = await vscode.workspace.fs.readFile(left);
            const rightData = await vscode.workspace.fs.readFile(right);
            const leftName = path.basename(left.fsPath);
            const rightName = path.basename(right.fsPath);

            // Build a synthetic URI for the diff tab.
            // Encode the original file paths so the diff can be restored
            // after a VS Code restart without re-prompting the user.
            const diffUri = vscode.Uri.parse(
                `hexana-module:/${leftName} ↔ ${rightName}?diff=true&left=${encodeURIComponent(left.toString())}&right=${encodeURIComponent(right.toString())}`,
            );
            const key = diffUri.toString();

            // Store the left side as the "document" bytes so openCustomDocument
            // can serve something, and store the diff payload separately.
            inMemoryModules.set(key, { data: leftData, fileName: `${leftName} ↔ ${rightName}` });
            pendingDiffs.set(key, { leftData, leftName, rightData, rightName });

            try {
                await vscode.commands.executeCommand(
                    "vscode.openWith",
                    diffUri,
                    WasmEditorProvider.viewType,
                );
            } catch (err) {
                console.error("[Hexana] Failed to open diff view:", err);
                vscode.window.showErrorMessage(`Failed to open diff view: ${err}`);
            }
        });
    }

    public static register(context: vscode.ExtensionContext): vscode.Disposable {
        // Register a virtual file-system for the hexana-module scheme so
        // that vscode.openWith can resolve nested-module URIs.
        context.subscriptions.push(
            vscode.workspace.registerFileSystemProvider("hexana-module", new HexanaModuleFS(), {
                isReadonly: true,
            }),
        );

        // Register the content provider for virtual WAT documents so that
        // VS Code's built-in Monaco editor can display WAT text.
        context.subscriptions.push(
            vscode.workspace.registerTextDocumentContentProvider("hexana-wat", watContentProvider),
        );

        const provider = new WasmEditorProvider(context);
        context.subscriptions.push(WasmEditorProvider.registerConfigureRuntimesCommand(provider));

        return vscode.window.registerCustomEditorProvider(
            WasmEditorProvider.viewType,
            provider,
            {
                supportsMultipleEditorsPerDocument: true,
                webviewOptions: { retainContextWhenHidden: true },
            },
        );
    }

    /** Shared by every open editor, so N webviews do not each hit the network. */
    private readonly featureCatalog: WasmFeatureCatalogProvider;

    /**
     * Live webviews and the document each one shows, so a settings change or a catalog
     * refresh reaches all of them - and so a runtime-target push can be routed to the
     * workspace folder the panel's own file lives in.
     */
    private readonly openWebviews = new Map<vscode.WebviewPanel, vscode.Uri>();

    /** One per workspace folder, rebuilt whenever the folders change; see `watchRuntimeTargets`. */
    private runtimeTargetsWatchers: vscode.FileSystemWatcher[] = [];

    constructor(private readonly context: vscode.ExtensionContext) {
        this.featureCatalog = new WasmFeatureCatalogProvider(context);
        context.subscriptions.push(this.featureCatalog);

        context.subscriptions.push(
            this.featureCatalog.onDidChange((json) => {
                this.broadcast({ type: "feature-catalog-loaded", featureCatalogJson: json });
            }),
        );

        // An edit by hand, a git pull, or a teammate's commit must take effect without
        // reopening the editor - the point of a project file over per-user settings.
        this.watchRuntimeTargets();
        // The watchers are bound to specific folders, so they have to be rebuilt when the set
        // of folders changes - otherwise a folder opened after activation stays unwatched.
        context.subscriptions.push(
            vscode.workspace.onDidChangeWorkspaceFolders(() => {
                this.watchRuntimeTargets();
                void this.pushRuntimeTargets();
            }),
        );
        // Registered once, rather than per rebuild, so repeated folder changes do not pile up
        // dead disposables in the extension context.
        context.subscriptions.push({ dispose: () => this.disposeRuntimeTargetsWatchers() });
    }

    /** Re-arms one `.hexana/runtimes.json` watcher per current workspace folder. */
    private watchRuntimeTargets(): void {
        this.disposeRuntimeTargetsWatchers();

        for (const folder of vscode.workspace.workspaceFolders ?? []) {
            const watcher = vscode.workspace.createFileSystemWatcher(
                new vscode.RelativePattern(folder, `${HEXANA_PROJECT_DIR}/${RUNTIME_TARGETS_FILE}`),
            );
            const push = () => void this.pushRuntimeTargets();
            watcher.onDidCreate(push);
            watcher.onDidChange(push);
            // Deleting the file is a change too: it means "nothing declared", and the webview
            // has to drop the warnings rather than keep showing the last declaration it saw.
            watcher.onDidDelete(push);
            this.runtimeTargetsWatchers.push(watcher);
        }
    }

    private disposeRuntimeTargetsWatchers(): void {
        for (const watcher of this.runtimeTargetsWatchers) {
            watcher.dispose();
        }
        this.runtimeTargetsWatchers = [];
    }

    /**
     * Sends every open webview the declaration that governs its own file.
     *
     * Read once per folder rather than once per panel: several editors on the same project is
     * the normal case, and they all read the same file.
     */
    private async pushRuntimeTargets(): Promise<void> {
        const byFolder = new Map<string, Promise<string>>();
        for (const [panel, uri] of this.openWebviews) {
            const key = runtimeTargetsFolder(uri)?.uri.toString() ?? "";
            let json = byFolder.get(key);
            if (json === undefined) {
                json = readRuntimeTargetsJson(uri);
                byFolder.set(key, json);
            }
            void panel.webview.postMessage({
                type: "runtime-targets-loaded",
                runtimeTargetsJson: await json,
            });
        }
    }

    private broadcast(message: Record<string, unknown>): void {
        for (const panel of this.openWebviews.keys()) {
            void panel.webview.postMessage(message);
        }
    }

    async openCustomDocument(
        uri: vscode.Uri,
        _openContext: vscode.CustomDocumentOpenContext,
        _token: vscode.CancellationToken,
    ): Promise<WasmDocument> {
        const key = uri.toString();
        const inMemory = inMemoryModules.get(key);
        if (inMemory) {
            const d = inMemory.data;
            extensionLib?.captureBinaryOpened(
                inMemory.fileName,
                new Int8Array(d.buffer, d.byteOffset, d.byteLength),
            );
            return new WasmDocument(uri, inMemory.data, inMemory.fileName);
        }

        let data = await vscode.workspace.fs.readFile(uri);
        let displayName: string | undefined = undefined;

        const fileName = path.basename(uri.fsPath);
        if (fileName.toLowerCase().endsWith(".gz") && !fileName.toLowerCase().endsWith(".tar.gz")) {
            try {
                data = zlib.gunzipSync(data);
                displayName = fileName.substring(0, fileName.length - 3);
            } catch (err) {
                console.error("[Hexana] Failed to decompress .gz file:", err);
            }
        }

        extensionLib?.captureBinaryOpened(
            displayName ?? path.basename(uri.fsPath),
            new Int8Array(data.buffer, data.byteOffset, data.byteLength),
        );
        return new WasmDocument(uri, data, displayName);
    }

    async resolveCustomEditor(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
        _token: vscode.CancellationToken,
    ): Promise<void> {
        webviewPanel.webview.options = {
            enableScripts: true,
            localResourceRoots: [
                vscode.Uri.joinPath(this.context.extensionUri, "media"),
            ],
        };

        webviewPanel.webview.html = getWebviewContent(webviewPanel.webview, this.context.extensionUri);

        this.openWebviews.set(webviewPanel, document.uri);
        webviewPanel.onDidDispose(() => this.openWebviews.delete(webviewPanel));

        // Once the webview signals it is ready, send the binary payload.
        webviewPanel.webview.onDidReceiveMessage(async (message) => {
            if (message.type === "ready") {
                // Check if this is a diff view.
                const diffKey = document.uri.toString();
                const diff = pendingDiffs.get(diffKey);
                if (diff) {
                    pendingDiffs.delete(diffKey);
                    const isNativeDiff = document.uri.query.includes("native=true");
                    const toPayload = (d: Uint8Array) =>
                        d instanceof Buffer
                            ? new Uint8Array(d.buffer, d.byteOffset, d.byteLength)
                            : d;
                    webviewPanel.webview.postMessage({
                        type: isNativeDiff ? "load-native-diff" : "load-diff",
                        leftData: toPayload(diff.leftData),
                        leftName: diff.leftName,
                        rightData: toPayload(diff.rightData),
                        rightName: diff.rightName,
                    });
                    return;
                }

                // Diff URI whose payload was lost (e.g. after VS Code restart).
                // Try to reload from disk using the file paths encoded in the URI.
                if (document.uri.query.includes("diff=true")) {
                    const params = new URLSearchParams(document.uri.query);
                    const isNativeDiff = params.get("native") === "true";
                    const leftUriStr = params.get("left");
                    const rightUriStr = params.get("right");
                    if (leftUriStr && rightUriStr) {
                        try {
                            const leftUri = vscode.Uri.parse(leftUriStr);
                            const rightUri = vscode.Uri.parse(rightUriStr);
                            const leftData = await vscode.workspace.fs.readFile(leftUri);
                            const rightData = await vscode.workspace.fs.readFile(rightUri);
                            const toPayload = (d: Uint8Array) =>
                                d instanceof Buffer
                                    ? new Uint8Array(d.buffer, d.byteOffset, d.byteLength)
                                    : d;
                            webviewPanel.webview.postMessage({
                                type: isNativeDiff ? "load-native-diff" : "load-diff",
                                leftData: toPayload(leftData),
                                leftName: path.basename(leftUri.fsPath),
                                rightData: toPayload(rightData),
                                rightName: path.basename(rightUri.fsPath),
                            });
                            return;
                        } catch (err) {
                            console.warn("[Hexana] Failed to reload diff files from disk:", err);
                        }
                    }
                    webviewPanel.webview.postMessage({
                        type: "load-expired",
                        message: "This diff session has expired and the original files could not be read. Please run \"Compare WASM With…\" again.",
                    });
                    return;
                }

                // Expired nested module or zip entry — try to reload from
                // the parent file using the metadata encoded in the URI.
                if (document.uri.scheme === "hexana-module" && document.data.byteLength === 0) {
                    const reloaded = await this.tryReloadExpiredModule(document.uri);
                    if (reloaded) {
                        // Re-populate in-memory store so subsequent reopens
                        // within this session don't need to re-extract.
                        const key = document.uri.toString();
                        const displayName = document.displayName ?? document.uri.path.replace(/^\//, "");
                        inMemoryModules.set(key, { data: reloaded, fileName: displayName });
                        const toPayload = (d: Uint8Array) =>
                            d instanceof Buffer
                                ? new Uint8Array(d.buffer, d.byteOffset, d.byteLength)
                                : d;
                        const reloadMsg: Record<string, unknown> = {
                            type: "load-wasm",
                            fileName: displayName,
                            filePath: document.uri.fsPath,
                            data: toPayload(reloaded),
                            wasmRuntime: getSelectedRuntime(),
                            showSemanticHighlighting: vscode.workspace.getConfiguration("hexana").get<boolean>("showSemanticHighlighting", true),
                        };
                        const reloadParent = this.resolveParentFileName(document.uri);
                        if (reloadParent) {
                            reloadMsg.parentFileName = reloadParent;
                        }
                        webviewPanel.webview.postMessage(reloadMsg);
                        return;
                    }

                    // For nested modules (archive/component members) we
                    // cannot extract on the TS side — send the parent bytes
                    // to the webview and let Kotlin re-extract the member.
                    const nestedReloaded = await this.tryReloadNestedModule(document.uri, webviewPanel);
                    if (nestedReloaded) {
                        return;
                    }

                    webviewPanel.webview.postMessage({
                        type: "load-expired",
                        message: "This session has expired and the original content could not be restored. Please reopen from the parent file.",
                    });
                    return;
                }

                const payload = document.data instanceof Buffer
                    ? new Uint8Array(document.data.buffer, document.data.byteOffset, document.data.byteLength)
                    : document.data;
                const loadMsg: Record<string, unknown> = {
                    type: "load-wasm",
                    fileName: document.displayName ?? document.uri.fsPath,
                    filePath: document.uri.fsPath,
                    data: payload,
                    wasmRuntime: getSelectedRuntime(),
                    showSemanticHighlighting: vscode.workspace.getConfiguration("hexana").get<boolean>("showSemanticHighlighting", true),
                    featureCatalogJson: this.featureCatalog.get(),
                    runtimeTargetsJson: await readRuntimeTargetsJson(document.uri),
                };
                if (document.displayName && document.displayName !== path.basename(document.uri.fsPath)) {
                    loadMsg.decompressed = true;
                }
                const parentFileName = this.resolveParentFileName(document.uri);
                if (parentFileName) {
                    loadMsg.parentFileName = parentFileName;
                }
                webviewPanel.webview.postMessage(loadMsg);
                this.restoreScriptTabs(document, webviewPanel);
                this.restoreSslKeyLog(document, webviewPanel);
            } else if (message.type === "open-module") {
                this.openNestedModule(document, message);
            } else if (message.type === "navigate-to-parent") {
                this.navigateToParent(document);
            } else if (message.type === "run-export") {
                runExport(document, message);
            } else if (message.type === "open-zip-entry") {
                this.openZipEntry(document, message);
            } else if (message.type === "open-tar-entry") {
                this.openTarEntry(document, message);
            } else if (message.type === "show-wat") {
                this.showWatEditor(document, message);
            } else if (message.type === "open-text-file") {
                this.openTextFile(message.fileName as string, message.content as string);
            } else if (message.type === "tab-selected") {
                extensionLib?.captureHexTabSelected(message.tabName, "wasm");
            } else if (message.type === "update-runtime-setting") {
                const runtime = message.runtime as string;
                if (runtime) {
                    vscode.workspace.getConfiguration("hexana").update(
                        "wasmRuntime",
                        runtime,
                        vscode.ConfigurationTarget.Global,
                    );
                }
            } else if (message.type === "save-runtime-targets") {
                await this.saveRuntimeTargets(
                    webviewPanel,
                    message.json as string,
                    message.schema as string | undefined,
                );
            } else if (message.type === "save-as") {
                this.saveAs(document);
            } else if (message.type === "overwrite-file") {
                this.overwriteFile(document, message);
            } else if (message.type === "save-copy") {
                this.saveCopy(document, message);
            } else if (message.type === "open-url") {
                const url = message.url as string;
                if (url) {
                    vscode.env.openExternal(vscode.Uri.parse(url));
                }
            } else if (message.type === "open-http-body") {
                this.openHttpBody(document, message as any);
            } else if (message.type === "open-dts") {
                this.openDtsReference(message.raw === true);
            } else if (message.type === "open-script-for-editing") {
                this.openScriptForEditing(
                    message.scriptPath as string,
                    message.jsConfigContent as string | undefined,
                );
            } else if (message.type === "create-script-from-template") {
                this.createScriptFromTemplate(
                    webviewPanel,
                    message.fileName as string,
                    message.template as string | undefined,
                    message.jsConfigContent as string | undefined,
                );
            } else if (message.type === "pick-script-file") {
                this.pickScriptFile(document, webviewPanel);
            } else if (message.type === "pick-ssl-key-log") {
                this.pickSslKeyLog(document, webviewPanel);
            } else if (message.type === "read-script-file") {
                this.readScriptFile(message.scriptPath as string, webviewPanel);
            } else if (message.type === "script-tab-added") {
                this.addScriptTabToState(document, message.scriptPath as string);
            } else if (message.type === "script-tab-removed") {
                this.removeScriptTabFromState(document, message.scriptPath as string);
            }
        });

        // Push semantic highlighting setting changes to the webview in real time.
        vscode.workspace.onDidChangeConfiguration((e) => {
            if (e.affectsConfiguration("hexana.showSemanticHighlighting")) {
                const enabled = vscode.workspace.getConfiguration("hexana").get<boolean>("showSemanticHighlighting", true);
                webviewPanel.webview.postMessage({
                    type: "update-setting",
                    key: "showSemanticHighlighting",
                    value: enabled,
                });
            }
        });
    }

    /**
     * Opens the WAT text in a native VS Code editor tab (Monaco) so that the
     * user gets syntax highlighting, search, and all standard editor features.
     */
    private async showWatEditor(
        document: WasmDocument,
        message: { watText: string; warning?: string },
    ): Promise<void> {
        const baseName = document.displayName ?? path.basename(document.uri.fsPath, ".wasm");
        const watUri = vscode.Uri.parse(
            `hexana-wat:/${baseName}.wat?source=${encodeURIComponent(document.uri.toString())}`,
        );

        watDocuments.set(watUri.toString(), message.watText);
        watContentProvider.update(watUri);

        extensionLib?.captureWatViewActivated();

        const doc = await vscode.workspace.openTextDocument(watUri);
        await vscode.window.showTextDocument(doc, {
            viewColumn: vscode.ViewColumn.Beside,
            preserveFocus: false,
            preview: true,
        });

        // Set language to WAT for syntax highlighting.
        await vscode.languages.setTextDocumentLanguage(doc, "wat");

        if (message.warning) {
            vscode.window.showWarningMessage(`WAT conversion warning: ${message.warning}`);
        }
    }

    /**
     * Opens an in-memory text document beside the current editor.
     * Used by the SBOM "View raw JSON" action to surface the embedded JSON.
     */
    private async openTextFile(fileName: string, content: string): Promise<void> {
        const ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        const language = ext === "json" ? "json" : ext || undefined;
        const doc = await vscode.workspace.openTextDocument({ content, language });
        await vscode.window.showTextDocument(doc, {
            viewColumn: vscode.ViewColumn.Beside,
            preserveFocus: false,
            preview: true,
        });
    }

    /**
     * Navigates back to the parent component that contains this submodule.
     *
     * The parent URI is encoded in the `?parent=` query parameter of the
     * nested module's synthetic `hexana-module:` URI.
     */
    private async navigateToParent(document: WasmDocument): Promise<void> {
        const params = new URLSearchParams(document.uri.query);
        const parentKey = params.get("parent");
        if (!parentKey) {
            return;
        }
        try {
            const parentUri = vscode.Uri.parse(decodeURIComponent(parentKey));
            await vscode.commands.executeCommand(
                "vscode.openWith",
                parentUri,
                WasmEditorProvider.viewType,
            );
        } catch (err) {
            console.error("[Hexana] Failed to navigate to parent:", err);
        }
    }

    /**
     * Attempts to reload an expired `hexana-module:` URI by reading the
     * parent file and re-extracting the nested content.
     *
     * Supports two kinds of nested URIs:
     * - Zip entries: `?parent=<uri>&entry=<name>` — scans the parent zip
     *   for the local file header matching the entry name and decompresses.
     * - Nested modules: `?parent=<uri>&index=<n>` — not re-extractable on
     *   the TS side (requires Kotlin parsing), so this falls back to `null`.
     *
     * The parent itself may be an expired `hexana-module:` URI, in which
     * case this method recurses to resolve the full chain.
     *
     * @returns the extracted bytes, or `null` if recovery is not possible.
     */
    private async tryReloadExpiredModule(uri: vscode.Uri): Promise<Uint8Array | null> {
        const params = new URLSearchParams(uri.query);
        const parentKey = params.get("parent");
        if (!parentKey) {
            return null;
        }

        // Resolve parent bytes — may itself be an expired in-memory URI.
        let parentBytes: Uint8Array;
        const parentUri = vscode.Uri.parse(decodeURIComponent(parentKey));
        try {
            const existing = inMemoryModules.get(parentUri.toString());
            if (existing && existing.data.byteLength > 0) {
                parentBytes = existing.data;
            } else if (parentUri.scheme === "hexana-module") {
                // Recursively reload the parent.
                const resolved = await this.tryReloadExpiredModule(parentUri);
                if (!resolved) {
                    return null;
                }
                parentBytes = resolved;
                // Cache so we don't re-extract on every child.
                const parentName = parentUri.path.replace(/^\//, "");
                inMemoryModules.set(parentUri.toString(), { data: parentBytes, fileName: parentName });
            } else {
                parentBytes = await vscode.workspace.fs.readFile(parentUri);
            }
        } catch {
            return null;
        }

        // Zip entry: scan the parent for the local file header by entry name.
        const entryName = params.get("entry");
        if (entryName) {
            return this.extractZipEntryByName(parentBytes, decodeURIComponent(entryName));
        }

        // Nested module (wasm component / archive member): the extraction
        // logic lives in Kotlin and is not available on the TS side.
        // We cannot re-extract here, so return null.
        return null;
    }

    /**
     * Handles expired nested module URIs (with `?parent=...&index=N`) by
     * reading the parent bytes and sending a `load-nested-module` message
     * to the webview, which uses Kotlin-side parsing to extract the member.
     *
     * @returns `true` if the message was sent, `false` if recovery failed.
     */
    private async tryReloadNestedModule(
        uri: vscode.Uri,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<boolean> {
        const params = new URLSearchParams(uri.query);
        const parentKey = params.get("parent");
        const indexStr = params.get("index");
        if (!parentKey || indexStr == null) {
            return false;
        }
        const moduleIndex = parseInt(indexStr, 10);
        if (isNaN(moduleIndex)) {
            return false;
        }

        // Resolve parent bytes.
        let parentBytes: Uint8Array;
        const parentUri = vscode.Uri.parse(decodeURIComponent(parentKey));
        try {
            const existing = inMemoryModules.get(parentUri.toString());
            if (existing && existing.data.byteLength > 0) {
                parentBytes = existing.data;
            } else if (parentUri.scheme === "hexana-module") {
                const resolved = await this.tryReloadExpiredModule(parentUri);
                if (!resolved) {
                    return false;
                }
                parentBytes = resolved;
                const parentName = parentUri.path.replace(/^\//, "");
                inMemoryModules.set(parentUri.toString(), { data: parentBytes, fileName: parentName });
            } else {
                parentBytes = await vscode.workspace.fs.readFile(parentUri);
            }
        } catch {
            return false;
        }

        const displayName = uri.path.replace(/^\//, "");
        const toPayload = (d: Uint8Array) =>
            d instanceof Buffer
                ? new Uint8Array(d.buffer, d.byteOffset, d.byteLength)
                : d;
        webviewPanel.webview.postMessage({
            type: "load-nested-module",
            parentData: toPayload(parentBytes),
            moduleIndex,
            fileName: displayName,
            wasmRuntime: getSelectedRuntime(),
        });
        return true;
    }

    /**
     * Scans a zip archive for a local file header matching the given entry
     * name and returns the decompressed bytes, or `null` on failure.
     */
    private extractZipEntryByName(archive: Uint8Array, entryName: string): Uint8Array | null {
        // Scan for local file headers (PK\x03\x04).
        const nameBytes = new TextEncoder().encode(entryName);
        for (let i = 0; i <= archive.length - 30; i++) {
            if (archive[i] !== 0x50 || archive[i + 1] !== 0x4B ||
                archive[i + 2] !== 0x03 || archive[i + 3] !== 0x04) {
                continue;
            }
            const compressionMethod = archive[i + 8] | (archive[i + 9] << 8);
            const compressedSize = archive[i + 18] | (archive[i + 19] << 8) |
                (archive[i + 20] << 16) | (archive[i + 21] << 24);
            const nameLen = archive[i + 26] | (archive[i + 27] << 8);
            const extraLen = archive[i + 28] | (archive[i + 29] << 8);

            if (i + 30 + nameLen > archive.length) {
                continue;
            }

            // Compare entry name.
            if (nameLen !== nameBytes.length) {
                continue;
            }
            let match = true;
            for (let j = 0; j < nameLen; j++) {
                if (archive[i + 30 + j] !== nameBytes[j]) {
                    match = false;
                    break;
                }
            }
            if (!match) {
                continue;
            }

            const dataOffset = i + 30 + nameLen + extraLen;
            try {
                const compressed = archive.slice(dataOffset, dataOffset + compressedSize);
                if (compressionMethod === 0) {
                    return compressed;
                } else if (compressionMethod === 8) {
                    return zlib.inflateRawSync(compressed);
                }
            } catch {
                return null;
            }
        }
        return null;
    }

    /**
     * Handles an `open-tar-entry` message from the webview by slicing the
     * raw bytes from the archive and opening the result in a new editor tab.
     * TAR entries are stored uncompressed, so no decompression is needed.
     */
    private async openTarEntry(
        document: WasmDocument,
        message: {
            name: string;
            dataOffset: number;
            size: number;
        },
    ): Promise<void> {
        const { name, dataOffset, size } = message;
        const childName = name.includes("/") ? name.substring(name.lastIndexOf("/") + 1) : name;
        if (!childName) {
            return;
        }

        const bytes = document.data.slice(dataOffset, dataOffset + size);

        const ext = childName.includes(".") ? childName.substring(childName.lastIndexOf(".") + 1).toLowerCase() : "";
        const binaryExtensions = new Set(["wasm", "class", "dex", "jar", "zip", "war", "apk", "bin", "elf", "exe", "so", "o", "a", "dll", "dylib", "tar", "tgz"]);

        if (binaryExtensions.has(ext) || isNativeBinary(bytes)) {
            const parentKey = document.uri.toString();
            const moduleUri = vscode.Uri.parse(
                `hexana-module:/${childName}?parent=${encodeURIComponent(parentKey)}&entry=${encodeURIComponent(name)}`,
            );
            const key = moduleUri.toString();
            inMemoryModules.set(key, { data: bytes, fileName: childName });

            try {
                await vscode.commands.executeCommand(
                    "vscode.openWith",
                    moduleUri,
                    WasmEditorProvider.viewType,
                );
            } catch (err) {
                console.error("[Hexana] Failed to open tar entry:", err);
                vscode.window.showErrorMessage(`Failed to open "${childName}": ${err}`);
            }
        } else {
            const content = new TextDecoder("utf-8").decode(bytes);
            const doc = await vscode.workspace.openTextDocument({ content, language: ext || undefined });
            await vscode.window.showTextDocument(doc, { preview: true });
        }
    }

    /**
     * Handles an `open-zip-entry` message from the webview by reading the
     * compressed bytes from the archive, decompressing them, and opening the
     * result in a new editor tab.
     */
    private async openZipEntry(
        document: WasmDocument,
        message: {
            name: string;
            dataOffset: number;
            compressedSize: number;
            uncompressedSize: number;
            compressionMethod: number;
        },
    ): Promise<void> {
        const { name, dataOffset, compressedSize, uncompressedSize, compressionMethod } = message;
        const childName = name.includes("/") ? name.substring(name.lastIndexOf("/") + 1) : name;
        if (!childName) {
            return;
        }

        let bytes: Uint8Array;
        try {
            const compressed = document.data.slice(dataOffset, dataOffset + compressedSize);
            if (compressionMethod === 0) {
                // Stored — no compression.
                bytes = compressed;
            } else if (compressionMethod === 8) {
                // Deflate — use raw inflate (no zlib/gzip header).
                bytes = zlib.inflateRawSync(compressed);
            } else {
                vscode.window.showWarningMessage(
                    `Unsupported compression method ${compressionMethod} for "${name}".`,
                );
                return;
            }
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to decompress "${name}": ${err}`);
            return;
        }

        // Check if the entry is a binary format we handle (wasm, class, jar, etc.)
        const ext = childName.includes(".") ? childName.substring(childName.lastIndexOf(".") + 1).toLowerCase() : "";
        const binaryExtensions = new Set(["wasm", "class", "dex", "jar", "zip", "war", "apk", "bin", "elf", "exe", "so", "o", "a", "dll", "dylib"]);

        if (binaryExtensions.has(ext) || isNativeBinary(bytes)) {
            // Open as a binary in our custom editor.
            const parentKey = document.uri.toString();
            const moduleUri = vscode.Uri.parse(
                `hexana-module:/${childName}?parent=${encodeURIComponent(parentKey)}&entry=${encodeURIComponent(name)}`,
            );
            const key = moduleUri.toString();
            inMemoryModules.set(key, { data: bytes, fileName: childName });

            try {
                await vscode.commands.executeCommand(
                    "vscode.openWith",
                    moduleUri,
                    WasmEditorProvider.viewType,
                );
            } catch (err) {
                console.error("[Hexana] Failed to open zip entry:", err);
                vscode.window.showErrorMessage(`Failed to open "${childName}": ${err}`);
            }
        } else {
            // Open as a text document.
            const content = new TextDecoder("utf-8").decode(bytes);
            const doc = await vscode.workspace.openTextDocument({ content, language: ext || undefined });
            await vscode.window.showTextDocument(doc, { preview: true });
        }
    }

    /**
     * Overwrites the original file with the edited bytes sent from the webview.
     */
    private async overwriteFile(
        document: WasmDocument,
        message: { data: number[] },
    ): Promise<void> {
        // In-memory modules (nested/submodules) cannot be overwritten.
        if (document.uri.scheme !== "file") {
            vscode.window.showWarningMessage("Cannot overwrite a virtual or in-memory document.");
            return;
        }
        try {
            const bytes = new Uint8Array(message.data);
            await vscode.workspace.fs.writeFile(document.uri, bytes);
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to overwrite file: ${err}`);
        }
    }

    /**
     * Saves a copy of the edited bytes as a sibling file next to the original.
     */
    private async saveCopy(
        document: WasmDocument,
        message: { outputName: string; data: number[] },
    ): Promise<void> {
        if (document.uri.scheme !== "file") {
            vscode.window.showWarningMessage("Cannot save a copy for a virtual document.");
            return;
        }
        try {
            const bytes = new Uint8Array(message.data);
            const parentUri = vscode.Uri.joinPath(document.uri, "..");
            const targetUri = vscode.Uri.joinPath(parentUri, message.outputName);
            await vscode.workspace.fs.writeFile(targetUri, bytes);
            vscode.window.showInformationMessage(`Saved copy to ${message.outputName}`);
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to save copy: ${err}`);
        }
    }

    /**
     * Resolves the display name of the parent file from a `hexana-module:` URI's
     * `?parent=` query parameter. Returns `undefined` for non-nested documents.
     */
    private resolveParentFileName(uri: vscode.Uri): string | undefined {
        if (uri.scheme !== "hexana-module") return undefined;
        const params = new URLSearchParams(uri.query);
        const parentKey = params.get("parent");
        if (!parentKey) return undefined;
        try {
            const parentUri = vscode.Uri.parse(decodeURIComponent(parentKey));
            // Check if the parent is also in-memory (has its own display name).
            const parentEntry = inMemoryModules.get(parentUri.toString());
            if (parentEntry?.fileName) return parentEntry.fileName;
            return path.basename(parentUri.fsPath);
        } catch {
            return undefined;
        }
    }

    /**
     * Shows a Save As dialog and writes the current document bytes to the
     * user-chosen location. Used for submodules and archive entries.
     */
    private async saveAs(document: WasmDocument): Promise<void> {
        const defaultName = document.displayName ?? path.basename(document.uri.fsPath);
        const uri = await vscode.window.showSaveDialog({
            defaultUri: vscode.Uri.file(defaultName),
            saveLabel: "Save As",
        });
        if (!uri) return;
        try {
            const bytes = document.data instanceof Buffer
                ? new Uint8Array(document.data.buffer, document.data.byteOffset, document.data.byteLength)
                : document.data;
            await vscode.workspace.fs.writeFile(uri, bytes);
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to save file: ${err}`);
        }
    }

    /**
     * Handles an `open-module` message from the webview by storing the nested
     * module bytes in memory and opening a new custom editor tab for them.
     */
    private async openNestedModule(
        parentDocument: WasmDocument,
        message: { moduleIndex: number; fileName: string; data: number[] },
    ): Promise<void> {
        const bytes = new Uint8Array(message.data);
        const moduleName = message.fileName || `module_${message.moduleIndex}.wasm`;

        // Build a unique URI for this nested module.
        const parentKey = parentDocument.uri.toString();
        const moduleUri = vscode.Uri.parse(
            `hexana-module:/${moduleName}?parent=${encodeURIComponent(parentKey)}&index=${message.moduleIndex}`,
        );

        // Store using the exact same string that openCustomDocument will produce
        // when it calls uri.toString().
        const key = moduleUri.toString();
        inMemoryModules.set(key, { data: bytes, fileName: moduleName });

        try {
            // Open with our custom editor view type.
            await vscode.commands.executeCommand(
                "vscode.openWith",
                moduleUri,
                WasmEditorProvider.viewType,
            );
        } catch (err) {
            console.error("[Hexana] Failed to open nested module:", err);
            vscode.window.showErrorMessage(
                `Failed to open nested module "${moduleName}": ${err}`,
            );
        }
    }

    // -------------------------------------------------------------------
    // HTTP body extraction
    // -------------------------------------------------------------------

    /**
     * Opens an HTTP response body extracted from a PCAP packet in a new
     * editor tab. The body bytes are sent from the webview as a number
     * array; we decode them and open an untitled document with the
     * appropriate language mode inferred from the Content-Type.
     */
    private async openHttpBody(
        _document: WasmDocument,
        message: { fileName: string; data: number[]; contentType: string; contentEncoding?: string },
    ): Promise<void> {
        try {
            const bytes = this.decompressHttpBody(new Uint8Array(message.data), message.contentEncoding);

            if (this.isTextContentType(message.contentType)) {
                const content = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
                const language = this.contentTypeToLanguageId(message.contentType);
                const doc = await vscode.workspace.openTextDocument({ content, language });
                await vscode.window.showTextDocument(doc, {
                    viewColumn: vscode.ViewColumn.Beside,
                    preserveFocus: false,
                    preview: true,
                });
            } else {
                // Unknown or binary content — open in the Hexana custom editor
                const fileName = message.fileName || "http-body.bin";
                const moduleUri = vscode.Uri.parse(
                    `hexana-module:/${fileName}?source=http-body`,
                );
                const key = moduleUri.toString();
                inMemoryModules.set(key, { data: bytes, fileName });
                await vscode.commands.executeCommand(
                    "vscode.openWith",
                    moduleUri,
                    WasmEditorProvider.viewType,
                );
            }
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to open HTTP body: ${err}`);
        }
    }

    /**
     * Decompresses an HTTP body according to the Content-Encoding header.
     * Supports gzip, x-gzip, and deflate. Returns the original bytes
     * unchanged when the encoding is absent, identity, or unrecognised.
     */
    private decompressHttpBody(bytes: Uint8Array, contentEncoding?: string): Uint8Array {
        if (!contentEncoding || bytes.length === 0) return bytes;
        const encoding = contentEncoding.trim().toLowerCase();
        try {
            switch (encoding) {
                case "gzip":
                case "x-gzip": {
                    const buf = zlib.gunzipSync(bytes);
                    return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
                }
                case "deflate": {
                    // "deflate" can be zlib-wrapped or raw; try zlib first.
                    try {
                        const buf = zlib.inflateSync(bytes);
                        return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
                    } catch {
                        const buf = zlib.inflateRawSync(bytes);
                        return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
                    }
                }
                case "identity":
                    return bytes;
                default:
                    return bytes;
            }
        } catch (err) {
            console.warn(`[Hexana] Failed to decompress HTTP body (${encoding}):`, err);
            return bytes;
        }
    }

    /**
     * Returns `true` when the Content-Type is a known text format that
     * should be opened as a regular text document rather than in Hexana.
     */
    private isTextContentType(contentType: string): boolean {
        if (!contentType) return false;
        const mime = contentType.split(";")[0].trim().toLowerCase();
        if (mime.startsWith("text/")) return true;
        switch (mime) {
            case "application/json":
            case "application/xml":
            case "application/javascript":
            case "application/xhtml+xml":
            case "application/svg+xml":
                return true;
            default:
                return false;
        }
    }

    /**
     * Maps an HTTP Content-Type value to a VS Code language identifier.
     */
    private contentTypeToLanguageId(contentType: string): string {
        if (!contentType) return "plaintext";
        const mime = contentType.split(";")[0].trim().toLowerCase();
        switch (mime) {
            case "application/json": return "json";
            case "application/xml":
            case "text/xml": return "xml";
            case "text/html": return "html";
            case "text/css": return "css";
            case "text/javascript":
            case "application/javascript": return "javascript";
            case "text/plain": return "plaintext";
            default:
                if (mime.startsWith("text/")) return "plaintext";
                return "plaintext";
        }
    }

    // -------------------------------------------------------------------
    // Script tab support
    // -------------------------------------------------------------------

    /**
     * Opens a pre-built scripting reference in a new VS Code editor tab.
     *
     * @param raw when true, opens the format-specific type dump for
     *   `binary.raw` instead of the curated API reference
     */
    private async openDtsReference(raw: boolean = false): Promise<void> {
        try {
            const fileName = raw ? "hexana-scripting-raw.d.ts" : "hexana-scripting.d.ts";
            const dtsPath = path.join(this.context.extensionPath, fileName);
            const dtsUri = vscode.Uri.file(dtsPath);
            const bytes = await vscode.workspace.fs.readFile(dtsUri);
            const content = new TextDecoder("utf-8").decode(bytes);
            const doc = await vscode.workspace.openTextDocument({
                content,
                language: "typescript",
            });
            await vscode.window.showTextDocument(doc, {
                viewColumn: vscode.ViewColumn.Beside,
                preserveFocus: false,
                preview: true,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to open API reference: ${err}`);
        }
    }

    /**
     * Creates a runnable script from the starter template or a bundled example,
     * opens it in an editor, and reports it back to the webview as if the user
     * had picked it in the file dialog.
     *
     * Starting from a blank `.js` file is the main obstacle to writing a script
     * tab, because it requires knowing the API before typing anything. A file
     * that already produces output turns that into editing lines that work.
     *
     * @param fileName file name to create, e.g. `hexana-script.js`
     * @param template inline starter source; when omitted, the bundled example
     *   named [fileName] is copied instead
     */
    private async createScriptFromTemplate(
        webviewPanel: vscode.WebviewPanel,
        fileName: string,
        template?: string,
        jsConfigContent?: string,
    ): Promise<void> {
        try {
            const content = template ?? await this.readBundledExample(fileName);
            const directory = this.scriptsDirectory();
            await vscode.workspace.fs.createDirectory(directory);

            const target = await this.uniqueScriptUri(directory, fileName);
            await vscode.workspace.fs.writeFile(target, new TextEncoder().encode(content));
            await this.attachScriptTypes(directory, jsConfigContent);

            const doc = await vscode.workspace.openTextDocument(target);
            await vscode.window.showTextDocument(doc, {
                viewColumn: vscode.ViewColumn.Beside,
                preserveFocus: true,
                preview: false,
            });
            webviewPanel.webview.postMessage({
                type: "script-file-picked",
                scriptPath: target.fsPath,
                scriptContent: content,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to create script: ${err}`);
        }
    }

    /**
     * Opens an existing script for editing, attaching the type definitions first,
     * so the editor resolves `binary`, `print`, `table` and `row`.
     */
    private async openScriptForEditing(scriptPath: string, jsConfigContent?: string): Promise<void> {
        try {
            const uri = vscode.Uri.file(scriptPath);
            await this.attachScriptTypes(vscode.Uri.file(path.dirname(scriptPath)), jsConfigContent);
            const doc = await vscode.workspace.openTextDocument(uri);
            await vscode.window.showTextDocument(doc, { preview: false });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to open script: ${err}`);
        }
    }

    /**
     * Writes the scripting type definitions into a script's folder.
     *
     * A standalone `.js` file gets no automatic association with a sibling
     * `.d.ts`, so the `jsconfig.json` is what actually puts the types in scope.
     * It is only created when the folder has no JavaScript project config at all —
     * an existing one is the user's and already governs how the folder is
     * analysed. The `.d.ts` itself is rewritten whenever it differs, so it
     * refreshes when the extension updates.
     */
    private async attachScriptTypes(directory: vscode.Uri, jsConfigContent?: string): Promise<void> {
        const encoder = new TextEncoder();
        const decoder = new TextDecoder("utf-8");

        const typesUri = vscode.Uri.joinPath(directory, HEXANA_SCRIPT_TYPES_FILE);
        const expected = await this.readBundledTypes();
        let current: string | undefined;
        try {
            current = decoder.decode(await vscode.workspace.fs.readFile(typesUri));
        } catch {
            current = undefined;
        }
        if (current !== expected) {
            await vscode.workspace.fs.writeFile(typesUri, encoder.encode(expected));
        }

        const hasConfig = await Promise.all(
            [HEXANA_SCRIPT_JSCONFIG_FILE, "tsconfig.json"].map(async (name) => {
                try {
                    await vscode.workspace.fs.stat(vscode.Uri.joinPath(directory, name));
                    return true;
                } catch {
                    return false;
                }
            }),
        );
        if (!hasConfig.some(Boolean) && jsConfigContent) {
            await vscode.workspace.fs.writeFile(
                vscode.Uri.joinPath(directory, HEXANA_SCRIPT_JSCONFIG_FILE),
                encoder.encode(jsConfigContent),
            );
        }
    }

    /** The curated reference shipped with the extension. */
    private async readBundledTypes(): Promise<string> {
        const uri = vscode.Uri.file(path.join(this.context.extensionPath, HEXANA_SCRIPT_TYPES_FILE));
        return new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(uri));
    }

    /**
     * Writes the runtime targets edited in the webview to `.hexana/runtimes.json`, then
     * re-reads and pushes it so every open editor agrees on what is on disk.
     *
     * Written to the folder the editing panel's own file lives in, so in a multi-root
     * workspace the declaration lands next to the binary it was made for rather than in
     * whichever folder happens to be first.
     *
     * Needs a workspace folder: without one there is no project to declare targets for, and
     * the webview's `canEditRuntimeTargets` capability is what normally keeps the dialog from
     * being offered at all.
     */
    private async saveRuntimeTargets(
        webviewPanel: vscode.WebviewPanel,
        json: string,
        schema?: string,
    ): Promise<void> {
        const folder = runtimeTargetsFolder(this.openWebviews.get(webviewPanel));
        if (!folder) {
            void vscode.window.showWarningMessage(
                "Open a folder before configuring target runtimes: they are saved to " +
                `${HEXANA_PROJECT_DIR}/${RUNTIME_TARGETS_FILE} in the workspace.`,
            );
            return;
        }
        try {
            const directory = vscode.Uri.joinPath(folder.uri, HEXANA_PROJECT_DIR);
            await vscode.workspace.fs.createDirectory(directory);
            const encoder = new TextEncoder();
            // Both files are written only when they differ: they are committed to version
            // control, and confirming the dialog without changing anything should leave them
            // alone. (The same read-compare-write discipline `RuntimeTargetsStore` uses.)
            // The schema goes first, so the reference the settings file carries never points
            // at a file that is not there yet.
            if (schema) {
                const schemaFile = vscode.Uri.joinPath(directory, RUNTIME_TARGETS_SCHEMA_FILE);
                if (await this.needsWrite(schemaFile, schema)) {
                    await vscode.workspace.fs.writeFile(schemaFile, encoder.encode(schema));
                }
            }
            const file = vscode.Uri.joinPath(directory, RUNTIME_TARGETS_FILE);
            if (await this.needsWrite(file, json)) {
                await vscode.workspace.fs.writeFile(file, encoder.encode(json));
            }
            // The file watcher also fires, but pushing here makes the round-trip immediate for
            // the editor the user is looking at; editors on other folders keep their own.
            await this.pushRuntimeTargets();

        } catch (error) {
            void vscode.window.showErrorMessage(
                `Failed to save target runtimes: ${error instanceof Error ? error.message : String(error)}`,
            );
        }
    }

    /** Whether [file] is missing or holds something other than [content]. */
    private async needsWrite(file: vscode.Uri, content: string): Promise<boolean> {
        try {
            return new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(file)) !== content;
        } catch {
            return true;
        }
    }

    /**
     * Directory the created scripts live in: inside the workspace when there is
     * one, otherwise the extension's own storage. Defined once so the file picker
     * can start where the scripts already are.
     */
    private scriptsDirectory(): vscode.Uri {
        const folder = vscode.workspace.workspaceFolders?.[0];
        return folder
            ? vscode.Uri.joinPath(folder.uri, ".hexana", "scripts")
            : vscode.Uri.file(path.join(this.context.globalStorageUri.fsPath, "scripts"));
    }

    /** The scripts directory, or undefined when it does not exist yet. */
    private async existingScriptsDirectory(): Promise<vscode.Uri | undefined> {
        const directory = this.scriptsDirectory();
        try {
            const stat = await vscode.workspace.fs.stat(directory);
            return stat.type === vscode.FileType.Directory ? directory : undefined;
        } catch {
            return undefined;
        }
    }

    private async readBundledExample(fileName: string): Promise<string> {
        const uri = vscode.Uri.file(path.join(this.context.extensionPath, "hexana-scripts", fileName));
        return new TextDecoder("utf-8").decode(await vscode.workspace.fs.readFile(uri));
    }

    /** Never clobbers a script the user may have already edited. */
    private async uniqueScriptUri(directory: vscode.Uri, fileName: string): Promise<vscode.Uri> {
        const extension = path.extname(fileName) || ".js";
        const base = path.basename(fileName, extension);
        for (let index = 1; index < 1000; index++) {
            const candidate = vscode.Uri.joinPath(
                directory,
                index === 1 ? `${base}${extension}` : `${base}-${index}${extension}`,
            );
            try {
                await vscode.workspace.fs.stat(candidate);
            } catch {
                return candidate;
            }
        }
        throw new Error(`Could not find an unused name for ${fileName}`);
    }

    /**
     * Shows a file picker for `.js` files and sends the selected file's
     * path and content back to the webview.
     */
    private async pickScriptFile(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<void> {
        const uris = await vscode.window.showOpenDialog({
            canSelectMany: false,
            filters: { "JavaScript": ["js"] },
            title: "Select Script File",
            // Start where this dialog's own scripts are; VS Code falls back to the
            // last used location when the folder does not exist yet.
            defaultUri: await this.existingScriptsDirectory(),
        });
        if (!uris || uris.length === 0) return;
        const uri = uris[0];
        try {
            const bytes = await vscode.workspace.fs.readFile(uri);
            const content = new TextDecoder("utf-8").decode(bytes);
            webviewPanel.webview.postMessage({
                type: "script-file-picked",
                scriptPath: uri.fsPath,
                scriptContent: content,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to read script file: ${err}`);
        }
    }

    /**
     * Shows a file picker for an SSLKEYLOGFILE (NSS Key Log Format) and sends
     * its text content to the webview for TLS decryption of a pcap capture.
     * Cancelling posts nothing.
     */
    private async pickSslKeyLog(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<void> {
        const uris = await vscode.window.showOpenDialog({
            canSelectMany: false,
            title: "Select SSL Key Log File",
            filters: {
                "SSL key log": ["txt", "log", "keylog", "sslkeylog"],
                "All files": ["*"],
            },
            // A key log usually sits next to the capture it belongs to, so open
            // there rather than in the last used location.
            defaultUri: vscode.Uri.joinPath(document.uri, ".."),
        });
        if (!uris || uris.length === 0) return;
        const uri = uris[0];
        try {
            const bytes = await vscode.workspace.fs.readFile(uri);
            const content = new TextDecoder("utf-8").decode(bytes);
            // Remember the pairing so reopening the capture decrypts it without
            // a second trip through the picker. Only the path is stored.
            this.context.workspaceState.update(sslKeyLogStateKey(document), uri.toString());
            webviewPanel.webview.postMessage({
                type: "ssl-key-log-picked",
                keyLogPath: uri.fsPath,
                keyLogContent: content,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to read SSL key log file: ${err}`);
        }
    }

    /**
     * Re-sends the SSL key log this capture was last paired with, so a
     * reopened capture decrypts itself. A pairing whose key log has since been
     * deleted or moved is forgotten rather than reported: the user did not ask
     * for anything here, so there is nothing to fail.
     */
    private async restoreSslKeyLog(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<void> {
        const key = sslKeyLogStateKey(document);
        const stored = this.context.workspaceState.get<string>(key);
        if (!stored) return;
        try {
            const uri = vscode.Uri.parse(stored);
            const bytes = await vscode.workspace.fs.readFile(uri);
            webviewPanel.webview.postMessage({
                type: "ssl-key-log-picked",
                keyLogPath: uri.fsPath,
                keyLogContent: new TextDecoder("utf-8").decode(bytes),
            });
        } catch {
            this.context.workspaceState.update(key, undefined);
        }
    }

    /**
     * Re-reads a script file from disk and sends the content to the webview.
     */
    private async readScriptFile(
        scriptPath: string,
        webviewPanel: vscode.WebviewPanel,
    ): Promise<void> {
        try {
            const uri = vscode.Uri.file(scriptPath);
            const bytes = await vscode.workspace.fs.readFile(uri);
            const content = new TextDecoder("utf-8").decode(bytes);
            webviewPanel.webview.postMessage({
                type: "script-file-content",
                scriptPath,
                scriptContent: content,
            });
        } catch (err) {
            vscode.window.showErrorMessage(`Failed to read script file: ${err}`);
        }
    }

    /**
     * Persists a newly added script tab path in workspace state.
     */
    private addScriptTabToState(document: WasmDocument, scriptPath: string): void {
        const key = `hexana.scriptTabs.${document.uri.toString()}`;
        const existing: string[] = this.context.workspaceState.get(key, []);
        if (!existing.includes(scriptPath)) {
            this.context.workspaceState.update(key, [...existing, scriptPath]);
        }
    }

    /**
     * Removes a script tab path from workspace state.
     */
    private removeScriptTabFromState(document: WasmDocument, scriptPath: string): void {
        const key = `hexana.scriptTabs.${document.uri.toString()}`;
        const existing: string[] = this.context.workspaceState.get(key, []);
        this.context.workspaceState.update(key, existing.filter(p => p !== scriptPath));
    }

    /**
     * Sends persisted script tab paths to the webview so it can restore them.
     */
    private restoreScriptTabs(
        document: WasmDocument,
        webviewPanel: vscode.WebviewPanel,
    ): void {
        const key = `hexana.scriptTabs.${document.uri.toString()}`;
        const paths: string[] = this.context.workspaceState.get(key, []);
        if (paths.length > 0) {
            webviewPanel.webview.postMessage({
                type: "restore-script-tabs",
                scriptPaths: paths,
            });
        }
    }
}

/** Workspace-state key holding the SSL key log paired with a capture. */
function sslKeyLogStateKey(document: WasmDocument): string {
    return `hexana.sslKeyLog.${document.uri.toString()}`;
}

/**
 * Lightweight document model that holds the raw bytes of a `.wasm` file
 * or an in-memory nested module.
 */
class WasmDocument implements vscode.CustomDocument {
    /** Optional display name override (used for nested modules). */
    public readonly displayName?: string;

    constructor(
        public readonly uri: vscode.Uri,
        public readonly data: Uint8Array,
        displayName?: string,
    ) {
        this.displayName = displayName;
    }

    dispose(): void {
        // Clean up in-memory entry if present.
        inMemoryModules.delete(this.uri.toString());
    }
}

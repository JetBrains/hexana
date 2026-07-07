import * as path from "path";
import * as zlib from "zlib";
import * as vscode from "vscode";
import { getWebviewContent } from "./webviewContent";
import { extensionLib, getSelectedRuntime, runExport } from "./WasmRunConfigurations";

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
        if (!entry) {
            throw vscode.FileSystemError.FileNotFound(uri);
        }
        return {
            type: vscode.FileType.File,
            ctime: 0,
            mtime: 0,
            size: entry.data.byteLength,
        };
    }

    readFile(uri: vscode.Uri): Uint8Array {
        const entry = inMemoryModules.get(uri.toString());
        if (!entry) {
            throw vscode.FileSystemError.FileNotFound(uri);
        }
        return entry.data;
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

        return vscode.window.registerCustomEditorProvider(
            WasmEditorProvider.viewType,
            new WasmEditorProvider(context),
            {
                supportsMultipleEditorsPerDocument: true,
                webviewOptions: { retainContextWhenHidden: true },
            },
        );
    }

    constructor(private readonly context: vscode.ExtensionContext) {}

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
        const data = await vscode.workspace.fs.readFile(uri);
        extensionLib?.captureBinaryOpened(
            path.basename(uri.fsPath),
            new Int8Array(data.buffer, data.byteOffset, data.byteLength),
        );
        return new WasmDocument(uri, data);
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
                vscode.Uri.joinPath(this.context.extensionUri, "webview", "build", "kotlin-webpack", "js", "productionExecutable"),
            ],
        };

        webviewPanel.webview.html = getWebviewContent(webviewPanel.webview, this.context.extensionUri);

        // Once the webview signals it is ready, send the binary payload.
        webviewPanel.webview.onDidReceiveMessage((message) => {
            if (message.type === "ready") {
                const payload = document.data instanceof Buffer
                    ? new Uint8Array(document.data.buffer, document.data.byteOffset, document.data.byteLength)
                    : document.data;
                webviewPanel.webview.postMessage({
                    type: "load-wasm",
                    fileName: document.displayName ?? document.uri.fsPath,
                    filePath: document.uri.fsPath,
                    data: payload,
                    wasmRuntime: getSelectedRuntime(),
                });
                this.restoreScriptTabs(document, webviewPanel);
            } else if (message.type === "open-module") {
                this.openNestedModule(document, message);
            } else if (message.type === "navigate-to-parent") {
                this.navigateToParent(document);
            } else if (message.type === "run-export") {
                runExport(document, message);
            } else if (message.type === "open-zip-entry") {
                this.openZipEntry(document, message);
            } else if (message.type === "show-wat") {
                this.showWatEditor(document, message);
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
            } else if (message.type === "overwrite-file") {
                this.overwriteFile(document, message);
            } else if (message.type === "save-copy") {
                this.saveCopy(document, message);
            } else if (message.type === "open-url") {
                const url = message.url as string;
                if (url) {
                    vscode.env.openExternal(vscode.Uri.parse(url));
                }
            } else if (message.type === "open-dts") {
                this.openDtsReference();
            } else if (message.type === "pick-script-file") {
                this.pickScriptFile(document, webviewPanel);
            } else if (message.type === "read-script-file") {
                this.readScriptFile(message.scriptPath as string, webviewPanel);
            } else if (message.type === "script-tab-added") {
                this.addScriptTabToState(document, message.scriptPath as string);
            } else if (message.type === "script-tab-removed") {
                this.removeScriptTabFromState(document, message.scriptPath as string);
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

        if (binaryExtensions.has(ext)) {
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
    // Script tab support
    // -------------------------------------------------------------------

    /**
     * Opens the pre-built `hexana-scripting.d.ts` API reference in a new
     * VS Code editor tab.
     */
    private async openDtsReference(): Promise<void> {
        try {
            const dtsPath = path.join(this.context.extensionPath, "hexana-scripting.d.ts");
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

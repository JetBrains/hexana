import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { getWebviewContent } from "./webviewContent";
import { execFileSync } from "child_process";
import type * as ExtensionLib from "./extension-lib";

// Kotlin/JS extension library — provides binary analysis, dependency resolution,
// and shell utilities backed by the shared wasmParser / plugins-shared modules.
// eslint-disable-next-line @typescript-eslint/no-var-requires
const extensionLib: typeof ExtensionLib | null = (() => {
    try {
        const mod = require("../extension-lib/build/kotlin-webpack/js/productionExecutable/extension-lib.js");
        return mod.hexanaExtensionLib.org.jetbrains.hexana.vscode.lib;
    } catch {
        console.warn("[Hexana] extension-lib not found — falling back to built-in helpers");
        return null;
    }
})();

/**
 * Node.js implementation of the FileSystemAccess interface expected by
 * the Kotlin/JS dependency resolver.
 */
const nodeFileSystemAccess: ExtensionLib.FileSystemAccess = {
    listDirectory(dir: string): Array<{ name: string; isDirectory: boolean; isFile: boolean }> {
        try {
            return fs.readdirSync(dir, { withFileTypes: true }).map(e => ({
                name: e.name,
                isDirectory: e.isDirectory(),
                isFile: e.isFile(),
            }));
        } catch {
            return [];
        }
    },
    readFile(p: string): Int8Array | null {
        try {
            const buf = fs.readFileSync(p);
            return new Int8Array(buf.buffer, buf.byteOffset, buf.byteLength);
        } catch {
            return null;
        }
    },
    fileExists(p: string): boolean {
        return fs.existsSync(p);
    },
    parentDir(p: string): string {
        return path.dirname(p);
    },
    joinPath(...parts: string[]): string {
        return path.join(...parts);
    },
    pathRoot(p: string): string {
        return path.parse(p).root;
    },
};

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
            extensionLib?.captureWasmFileOpened(inMemory.data.byteLength);
            return new WasmDocument(uri, inMemory.data, inMemory.fileName);
        }
        const data = await vscode.workspace.fs.readFile(uri);
        extensionLib?.captureWasmFileOpened(data.byteLength);
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
                webviewPanel.webview.postMessage({
                    type: "load-wasm",
                    fileName: document.displayName ?? document.uri.fsPath,
                    data: Array.from(document.data),
                });
            } else if (message.type === "open-module") {
                this.openNestedModule(document, message);
            } else if (message.type === "run-export") {
                this.runExport(document, message);
            } else if (message.type === "show-wat") {
                this.showWatEditor(document, message);
            } else if (message.type === "tab-selected") {
                extensionLib?.captureHexTabSelected(message.tabName);
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
     * Handles a `run-export` message from the webview by executing the Wasm
     * module in a VS Code terminal using wasmtime.
     *
     * When the webview sends stub modules (for core-module non-function
     * imports) or component import names, this method writes temporary
     * stub files and passes `--preload` / `-W unknown-imports-default`
     * flags, or attempts component composition via `wasm-tools compose`
     * or `wac plug`.
     */
    private runExport(
        document: WasmDocument,
        message: {
            exportName: string;
            invokeArgs: string;
            programArgs: string;
            isComponent: boolean;
            hasImports?: boolean;
            stubs?: Array<{ moduleName: string; wasmBytes: number[] }>;
            componentImports?: string[];
        },
    ): void {
        if (!extensionLib) {
            vscode.window.showErrorMessage("Cannot run: extension library is not available.");
            return;
        }

        const filePath = document.uri.fsPath;
        if (!filePath) {
            vscode.window.showErrorMessage("Cannot run: file path is not available for in-memory modules.");
            return;
        }

        if (message.isComponent && message.componentImports && message.componentImports.length > 0) {
            this.runComponentExport(document, message);
            return;
        }

        const args: string[] = ["run"];

        // Handle core-module import stubs
        if (!message.isComponent && message.hasImports) {
            args.push("-W", "unknown-imports-default");

            if (message.stubs && message.stubs.length > 0) {
                const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "hexana-wasm-stubs-"));
                for (const stub of message.stubs) {
                    const stubFile = path.join(tempDir, `${stub.moduleName}.wasm`);
                    fs.writeFileSync(stubFile, Buffer.from(stub.wasmBytes));
                    args.push("--preload", `${stub.moduleName}=${stubFile}`);
                }
            }
        }

        if (message.invokeArgs) {
            args.push(...extensionLib.splitShellArgs(message.invokeArgs));
        }

        args.push(filePath);

        if (message.programArgs) {
            args.push(...extensionLib.splitShellArgs(message.programArgs));
        }

        const exportName = message.exportName || path.basename(filePath, ".wasm");
        const terminalName = `Wasm: ${exportName}`;

        const isWindows = os.platform() === "win32";
        const wasmtimeCmd = this.getWasmtimeCommand(isWindows);
        const terminal = vscode.window.createTerminal({ name: terminalName });
        terminal.show();
        terminal.sendText(`${wasmtimeCmd} ${args.map((a: string) => extensionLib.shellEscape(a, isWindows)).join(" ")}`);
    }

    /**
     * Handles running a component-model export by first resolving
     * dependencies from the filesystem (including transitive deps),
     * composing them using `wasm-tools compose` or `wac plug`, and
     * then running the result.
     */
    private runComponentExport(
        document: WasmDocument,
        message: {
            exportName: string;
            invokeArgs: string;
            programArgs: string;
            isComponent: boolean;
            componentImports?: string[];
        },
    ): void {
        if (!extensionLib) {
            vscode.window.showErrorMessage("Cannot run: extension library is not available.");
            return;
        }

        const filePath = document.uri.fsPath;
        const componentImports = message.componentImports || [];

        // Resolve dependencies using the Kotlin/JS library
        const scanRoots = this.buildScanRoots(filePath);
        const resolution = extensionLib.resolveAllDependencies(
            componentImports, scanRoots, filePath, nodeFileSystemAccess,
        );
        const allResolved: Record<string, string> = {};
        for (const dep of resolution.resolved) {
            allResolved[dep.importName] = dep.filePath;
        }

        let effectiveWasmPath = filePath;

        if (Object.keys(allResolved).length > 0) {
            const composeResult = this.composeComponents(filePath, allResolved);
            if (composeResult.composed && composeResult.wasmPath) {
                effectiveWasmPath = composeResult.wasmPath;
            } else if (composeResult.error) {
                vscode.window.showWarningMessage(
                    `Component composition failed: ${composeResult.error}. Running original file.`,
                );
            }
        }

        const args: string[] = ["run"];

        if (message.invokeArgs) {
            args.push(...extensionLib.splitShellArgs(message.invokeArgs));
        }

        args.push(effectiveWasmPath);

        if (message.programArgs) {
            args.push(...extensionLib.splitShellArgs(message.programArgs));
        }

        const exportName = message.exportName || path.basename(filePath, ".wasm");
        const terminalName = `Wasm: ${exportName}`;

        const isWindows = os.platform() === "win32";
        const wasmtimeCmd = this.getWasmtimeCommand(isWindows);
        const terminal = vscode.window.createTerminal({ name: terminalName });
        terminal.show();
        terminal.sendText(`${wasmtimeCmd} ${args.map((a: string) => extensionLib.shellEscape(a, isWindows)).join(" ")}`);
    }

    /**
     * Returns the wasmtime command to use, respecting the `hexana.wasmtimePath`
     * setting. When the setting is empty, falls back to the default `wasmtime`
     * (or `wasmtime.exe` on Windows) resolved from the system PATH.
     */
    private getWasmtimeCommand(isWindows: boolean): string {
        const config = vscode.workspace.getConfiguration("hexana");
        const customPath = config.get<string>("wasmtimePath", "").trim();
        if (customPath) {
            return customPath;
        }
        return isWindows ? "wasmtime.exe" : "wasmtime";
    }

    /**
     * Builds a list of directories to scan for `.wasm` dependency files.
     * Uses the workspace folder and walks up from the wasm file to find
     * a likely workspace root (directory containing `Cargo.toml`,
     * `package.json`, or `.git`).
     */
    private buildScanRoots(wasmFilePath: string): string[] {
        const roots = new Set<string>();

        // Add workspace folders
        if (vscode.workspace.workspaceFolders) {
            for (const folder of vscode.workspace.workspaceFolders) {
                roots.add(folder.uri.fsPath);
            }
        }

        // Walk up from the wasm file to find a workspace root (delegated to Kotlin/JS)
        if (extensionLib) {
            const workspaceRoot: string | null = extensionLib.findWorkspaceRoot(wasmFilePath, nodeFileSystemAccess);
            if (workspaceRoot) {
                roots.add(workspaceRoot);
            }
        }

        return Array.from(roots);
    }

    /** Maximum number of iterative compose passes to prevent infinite loops. */
    private static readonly MAX_COMPOSE_PASSES = 10;

    /**
     * Attempts to compose component dependencies using `wasm-tools compose`
     * or `wac plug`.
     *
     * Because `wasm-tools compose` (and `wac plug`) only satisfy the
     * **direct** imports of the input component, transitive dependencies
     * require iterative composition: after each pass the output is checked
     * for remaining non-runtime component imports, and composition is
     * repeated until all imports are satisfied or no further progress can
     * be made.
     */
    private composeComponents(
        mainWasmPath: string,
        deps: Record<string, string>,
    ): { wasmPath: string; composed: boolean; error?: string } {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "hexana-wac-compose-"));

        // Try wasm-tools compose first
        const wasmToolsPath = this.resolveToolPath("wasm-tools");
        if (wasmToolsPath) {
            const result = this.composeIteratively(tempDir, mainWasmPath, deps,
                (input, d, output) => this.composeWithWasmTools(wasmToolsPath, input, d, output));
            if (result.composed) {return result;}
        }

        // Fall back to wac plug
        const wacPath = this.resolveToolPath("wac");
        if (wacPath) {
            const result = this.composeIteratively(tempDir, mainWasmPath, deps,
                (input, d, output) => this.composeWithWac(wacPath, input, d, output));
            if (result.composed) {return result;}
        }

        const toolNames = [wasmToolsPath ? "wasm-tools" : null, wacPath ? "wac" : null].filter(Boolean);
        const errorMsg = toolNames.length === 0
            ? "No composition tool found. Install 'wasm-tools' (cargo install wasm-tools) or 'wac' (cargo install wac-cli)."
            : `Component composition failed with ${toolNames.join(" and ")}.`;
        return { wasmPath: mainWasmPath, composed: false, error: errorMsg };
    }

    /**
     * Runs the given compose function iteratively until all non-runtime
     * component imports are satisfied or no further progress can be made.
     */
    private composeIteratively(
        tempDir: string,
        mainWasmPath: string,
        deps: Record<string, string>,
        composeFn: (input: string, deps: Record<string, string>, output: string) => { wasmPath: string; composed: boolean; error?: string },
    ): { wasmPath: string; composed: boolean; error?: string } {
        let currentInput = mainWasmPath;
        let remainingDeps = { ...deps };
        let lastResult: { wasmPath: string; composed: boolean; error?: string } | undefined;

        for (let pass = 1; pass <= WasmEditorProvider.MAX_COMPOSE_PASSES; pass++) {
            const outputFile = path.join(tempDir, `composed_pass${pass}.wasm`);
            const result = composeFn(currentInput, remainingDeps, outputFile);
            if (!result.composed) {return result;}

            lastResult = result;
            const unresolvedImports = this.findUnresolvedComponentImports(result.wasmPath);
            if (unresolvedImports.size === 0) {
                return result;
            }

            const nextDeps: Record<string, string> = {};
            for (const [name, filePath] of Object.entries(remainingDeps)) {
                if (unresolvedImports.has(name)) {
                    nextDeps[name] = filePath;
                }
            }
            if (Object.keys(nextDeps).length === 0 ||
                JSON.stringify(nextDeps) === JSON.stringify(remainingDeps)) {
                return result;
            }

            currentInput = result.wasmPath;
            remainingDeps = nextDeps;
        }

        return lastResult ?? { wasmPath: mainWasmPath, composed: false, error: "max compose passes reached" };
    }

    /**
     * Parses the given wasm file and returns the set of non-runtime component
     * import names that are still unresolved.
     */
    private findUnresolvedComponentImports(wasmPath: string): Set<string> {
        if (!extensionLib) {return new Set();}
        try {
            const bytes = fs.readFileSync(wasmPath);
            const int8 = new Int8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength);
            if (!extensionLib.isComponentBinary(int8)) {return new Set();}
            const importNames: string[] = Array.from(extensionLib.parseComponentImportNames(int8));
            return new Set(
                importNames.filter((name: string) => !extensionLib!.isWasiRuntimeImport(name)),
            );
        } catch {
            return new Set();
        }
    }

    private composeWithWasmTools(
        wasmToolsPath: string,
        mainWasmPath: string,
        deps: Record<string, string>,
        outputFile: string,
    ): { wasmPath: string; composed: boolean; error?: string } {
        const command = [wasmToolsPath, "compose", mainWasmPath];
        for (const depPath of Object.values(deps)) {
            command.push("-d", depPath);
        }
        command.push("-o", outputFile);
        return this.runTool(command, mainWasmPath, outputFile);
    }

    private composeWithWac(
        wacPath: string,
        mainWasmPath: string,
        deps: Record<string, string>,
        outputFile: string,
    ): { wasmPath: string; composed: boolean; error?: string } {
        const command = [wacPath, "plug", mainWasmPath];
        for (const depPath of Object.values(deps)) {
            command.push("--plug", depPath);
        }
        command.push("-o", outputFile);
        return this.runTool(command, mainWasmPath, outputFile);
    }

    private runTool(
        command: string[],
        mainWasmPath: string,
        outputFile: string,
    ): { wasmPath: string; composed: boolean; error?: string } {
        try {
            execFileSync(command[0], command.slice(1), {
                timeout: 30000,
                stdio: ["pipe", "pipe", "pipe"],
            });
            if (fs.existsSync(outputFile)) {
                return { wasmPath: outputFile, composed: true };
            }
            return { wasmPath: mainWasmPath, composed: false, error: "Output file not created" };
        } catch (e: any) {
            const stderr = e.stderr ? e.stderr.toString().trim() : "";
            return {
                wasmPath: mainWasmPath,
                composed: false,
                error: stderr || e.message || "unknown error",
            };
        }
    }

    /**
     * Resolves the path to a CLI tool, checking common locations.
     * On Windows, appends `.exe` to binary names and uses `where.exe`
     * instead of `which` to check PATH availability.
     */
    private resolveToolPath(toolName: string): string | undefined {
        const isWindows = os.platform() === "win32";
        const exeName = isWindows ? `${toolName}.exe` : toolName;
        const home = os.homedir();
        const candidates = [
            path.join(home, ".cargo", "bin", exeName),
            exeName,
        ];

        for (const candidate of candidates) {
            try {
                if (candidate === exeName) {
                    // Check if it's on PATH
                    const whichCmd = isWindows ? "where.exe" : "which";
                    execFileSync(whichCmd, [exeName], { stdio: "pipe" });
                    return exeName;
                } else if (fs.existsSync(candidate)) {
                    return candidate;
                }
            } catch {
                // Not found, try next
            }
        }
        return undefined;
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

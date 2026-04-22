import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { getWebviewContent } from "./webviewContent";
import { execFileSync } from "child_process";

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
            return new WasmDocument(uri, inMemory.data, inMemory.fileName);
        }
        const data = await vscode.workspace.fs.readFile(uri);
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
            }
        });
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
            args.push(...splitShellArgs(message.invokeArgs));
        }

        args.push(filePath);

        if (message.programArgs) {
            args.push(...splitShellArgs(message.programArgs));
        }

        const exportName = message.exportName || path.basename(filePath, ".wasm");
        const terminalName = `Wasm: ${exportName}`;

        const terminal = vscode.window.createTerminal({ name: terminalName });
        terminal.show();
        terminal.sendText(`wasmtime ${args.map(a => shellEscape(a)).join(" ")}`);
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
        const filePath = document.uri.fsPath;
        const componentImports = message.componentImports || [];

        // Resolve dependencies from the filesystem (with transitive resolution)
        const scanRoots = this.buildScanRoots(filePath);
        const resolved = this.resolveComponentDeps(componentImports, scanRoots, filePath);
        const allResolved = this.resolveTransitiveDeps(resolved, scanRoots, filePath);

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
            args.push(...splitShellArgs(message.invokeArgs));
        }

        args.push(effectiveWasmPath);

        if (message.programArgs) {
            args.push(...splitShellArgs(message.programArgs));
        }

        const exportName = message.exportName || path.basename(filePath, ".wasm");
        const terminalName = `Wasm: ${exportName}`;

        const terminal = vscode.window.createTerminal({ name: terminalName });
        terminal.show();
        terminal.sendText(`wasmtime ${args.map(a => shellEscape(a)).join(" ")}`);
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

        // Walk up from the wasm file to find a workspace root
        const workspaceRoot = this.findWorkspaceRoot(wasmFilePath);
        if (workspaceRoot) {
            roots.add(workspaceRoot);
        }

        return Array.from(roots);
    }

    private static readonly WORKSPACE_MARKERS = ["Cargo.toml", "package.json", ".git"];

    private findWorkspaceRoot(wasmFilePath: string): string | undefined {
        let dir = path.dirname(wasmFilePath);
        const root = path.parse(dir).root;
        while (dir && dir !== root) {
            for (const marker of WasmEditorProvider.WORKSPACE_MARKERS) {
                if (fs.existsSync(path.join(dir, marker))) {
                    return dir;
                }
            }
            dir = path.dirname(dir);
        }
        return undefined;
    }

    /**
     * Scans directories for `.wasm` component files whose exports match
     * the given import names.  Uses a `needed` map from base name
     * (without version) to the original import name so that version-
     * mismatched names still resolve correctly.
     */
    private resolveComponentDeps(
        importNames: string[],
        scanRoots: string[],
        excludePath: string,
    ): Record<string, string> {
        const resolved: Record<string, string> = {};
        // Map: baseName (without @version) → original import name
        const needed: Record<string, string> = {};
        for (const name of importNames) {
            needed[splitNameAndVersion(name)] = name;
        }
        const visited = new Set<string>([excludePath]);

        for (const root of scanRoots) {
            if (Object.keys(needed).length === Object.keys(resolved).length) {break;}
            this.scanDirectory(root, needed, resolved, visited);
        }

        return resolved;
    }

    /**
     * Transitively resolves component dependencies.  For each resolved
     * file that is itself a component, its imports are resolved too.
     * Already-visited files are skipped to avoid cycles.
     */
    private resolveTransitiveDeps(
        directDeps: Record<string, string>,
        scanRoots: string[],
        excludePath: string,
    ): Record<string, string> {
        const allResolved: Record<string, string> = { ...directDeps };
        const visitedPaths = new Set<string>([excludePath]);
        const queue = [...new Set(Object.values(directDeps))];

        while (queue.length > 0) {
            const depPath = queue.shift()!;
            if (visitedPaths.has(depPath)) {continue;}
            visitedPaths.add(depPath);

            let depBytes: Buffer;
            try {
                depBytes = fs.readFileSync(depPath);
            } catch {
                continue;
            }

            if (!isComponentBinary(depBytes)) {continue;}

            const depExportNames = parseComponentExportNames(depBytes);
            if (depExportNames.length === 0) {continue;}

            const depImportNames = parseComponentImportNames(depBytes);
            const transImports = depImportNames.filter(n => !(n in allResolved) && !isWasiRuntimeImport(n));
            if (transImports.length === 0) {continue;}

            const transResolved = this.resolveComponentDeps(transImports, scanRoots, excludePath);
            for (const [name, filePath] of Object.entries(transResolved)) {
                if (!(name in allResolved)) {
                    allResolved[name] = filePath;
                    queue.push(filePath);
                }
            }
        }

        return allResolved;
    }

    private scanDirectory(
        dir: string,
        needed: Record<string, string>,
        resolved: Record<string, string>,
        visited: Set<string>,
    ): void {
        let entries: fs.Dirent[];
        try {
            entries = fs.readdirSync(dir, { withFileTypes: true });
        } catch {
            return;
        }

        for (const entry of entries) {
            if (Object.keys(resolved).length >= Object.keys(needed).length) {return;}
            const fullPath = path.join(dir, entry.name);

            if (entry.isDirectory()) {
                this.scanDirectory(fullPath, needed, resolved, visited);
            } else if (entry.name.endsWith(".wasm") && !visited.has(fullPath)) {
                visited.add(fullPath);
                this.matchComponentExports(fullPath, needed, resolved);
            }
        }
    }

    /**
     * Checks if a `.wasm` file is a component whose exports satisfy any
     * of the needed imports.  Uses proper binary parsing of the component
     * export section (section ID 11) to extract export names.
     */
    private matchComponentExports(
        filePath: string,
        needed: Record<string, string>,
        resolved: Record<string, string>,
    ): void {
        try {
            const bytes = fs.readFileSync(filePath);
            if (!isComponentBinary(bytes)) {return;}

            const exportNames = parseComponentExportNames(bytes);
            for (const exportName of exportNames) {
                const baseExportName = splitNameAndVersion(exportName);
                const originalImport = needed[baseExportName];
                if (originalImport !== undefined && !(originalImport in resolved)) {
                    resolved[originalImport] = filePath;
                }
            }
        } catch {
            // Skip files that can't be read or parsed
        }
    }

    /**
     * Attempts to compose component dependencies using `wasm-tools compose`
     * or `wac plug`.
     */
    private composeComponents(
        mainWasmPath: string,
        deps: Record<string, string>,
    ): { wasmPath: string; composed: boolean; error?: string } {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "hexana-wac-compose-"));
        const outputFile = path.join(tempDir, "composed.wasm");

        // Try wasm-tools compose first
        const wasmToolsPath = this.resolveToolPath("wasm-tools");
        if (wasmToolsPath) {
            const result = this.composeWithWasmTools(wasmToolsPath, mainWasmPath, deps, outputFile);
            if (result.composed) {return result;}
        }

        // Fall back to wac plug
        const wacPath = this.resolveToolPath("wac");
        if (wacPath) {
            const result = this.composeWithWac(wacPath, mainWasmPath, deps, outputFile);
            if (result.composed) {return result;}
        }

        const toolNames = [wasmToolsPath ? "wasm-tools" : null, wacPath ? "wac" : null].filter(Boolean);
        const errorMsg = toolNames.length === 0
            ? "No composition tool found. Install 'wasm-tools' (cargo install wasm-tools) or 'wac' (cargo install wac-cli)."
            : `Component composition failed with ${toolNames.join(" and ")}.`;
        return { wasmPath: mainWasmPath, composed: false, error: errorMsg };
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
     */
    private resolveToolPath(toolName: string): string | undefined {
        const home = os.homedir();
        const candidates = [
            path.join(home, ".cargo", "bin", toolName),
            toolName,
        ];

        for (const candidate of candidates) {
            try {
                if (candidate === toolName) {
                    // Check if it's on PATH
                    execFileSync("which", [toolName], { stdio: "pipe" });
                    return toolName;
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
 * Splits a string into shell-like arguments, respecting double-quoted segments.
 *
 * For example `--invoke "add(1, 2)"` becomes `["--invoke", "add(1, 2)"]`.
 * This is needed because the webview sends pre-formatted argument strings
 * that may contain quoted tokens with internal spaces.
 */
function splitShellArgs(input: string): string[] {
    const result: string[] = [];
    let current = "";
    let inQuotes = false;
    for (let i = 0; i < input.length; i++) {
        const ch = input[i];
        if (ch === '"') {
            inQuotes = !inQuotes;
        } else if (ch === " " && !inQuotes) {
            if (current.length > 0) {
                result.push(current);
                current = "";
            }
        } else {
            current += ch;
        }
    }
    if (current.length > 0) {
        result.push(current);
    }
    return result;
}

/**
 * Escapes a single shell argument for safe use in a terminal command.
 */
function shellEscape(arg: string): string {
    if (/^[a-zA-Z0-9_./:=-]+$/.test(arg)) {
        return arg;
    }
    return `'${arg.replace(/'/g, "'\\''")}'`;
}

// ---------------------------------------------------------------------------
// Component binary helpers — minimal parsing of the Wasm component format
// to extract import/export names without a full Wasm parser.
// ---------------------------------------------------------------------------

/** WASI namespace prefix provided by the runtime. */
function isWasiRuntimeImport(name: string): boolean {
    return name.startsWith("wasi:");
}

/**
 * Strips the `@version` suffix from a WIT name.
 * E.g. `component:foo/bar@0.1.0` → `component:foo/bar`.
 */
function splitNameAndVersion(name: string): string {
    const idx = name.lastIndexOf("@");
    return idx >= 0 ? name.substring(0, idx) : name;
}

/**
 * Returns `true` when the buffer starts with the Wasm component magic:
 * `\0asm` with version byte `0x0d` and layer ≥ 1.
 */
function isComponentBinary(bytes: Buffer | Uint8Array): boolean {
    if (bytes.length < 8) {return false;}
    if (bytes[0] !== 0x00 || bytes[1] !== 0x61 || bytes[2] !== 0x73 || bytes[3] !== 0x6d) {
        return false;
    }
    // version field: bytes 4–7 as little-endian u32.
    // Low 16 bits = version, high 16 bits = layer.
    // Component: version == 0x0d, layer >= 1.
    const version = bytes[4] | (bytes[5] << 8);
    const layer = bytes[6] | (bytes[7] << 8);
    return version === 0x0d && layer >= 1;
}

/** Reads an unsigned LEB128 value from `bytes` starting at `offset`. */
function readLeb128(bytes: Buffer | Uint8Array, offset: number): { value: number; bytesRead: number } {
    let value = 0;
    let shift = 0;
    let bytesRead = 0;
    while (offset + bytesRead < bytes.length) {
        const b = bytes[offset + bytesRead];
        value |= (b & 0x7f) << shift;
        bytesRead++;
        if ((b & 0x80) === 0) {break;}
        shift += 7;
    }
    return { value, bytesRead };
}

/** Reads a length-prefixed UTF-8 string from `bytes` at `offset`. */
function readWasmString(bytes: Buffer | Uint8Array, offset: number): { value: string; bytesRead: number } {
    const len = readLeb128(bytes, offset);
    const start = offset + len.bytesRead;
    const end = start + len.value;
    if (end > bytes.length) {return { value: "", bytesRead: len.bytesRead };}
    const strBytes = bytes.slice(start, end);
    const value = Buffer.from(strBytes).toString("utf-8");
    return { value, bytesRead: len.bytesRead + len.value };
}

/**
 * Iterates over the top-level sections in a Wasm component binary.
 * For each section yields the section ID, content offset, and content size.
 */
function* iterateComponentSections(bytes: Buffer | Uint8Array): Generator<{ id: number; contentOffset: number; contentSize: number }> {
    let offset = 8; // skip magic + version
    while (offset < bytes.length) {
        const id = bytes[offset];
        offset++;
        const size = readLeb128(bytes, offset);
        const contentOffset = offset + size.bytesRead;
        const contentSize = size.value;
        yield { id, contentOffset, contentSize };
        offset = contentOffset + contentSize;
    }
}

/**
 * Skips a `componentSort` encoding.  Returns the number of bytes consumed.
 * Format: one byte; if the byte is 0x00 an additional `componentCoreSort`
 * byte follows.
 */
function skipComponentSort(bytes: Buffer | Uint8Array, offset: number): number {
    const b = bytes[offset];
    // 0x00 means "core sort" — an extra discriminant byte follows
    return b === 0x00 ? 2 : 1;
}

/**
 * Skips a `componentSortWithIndex` encoding (sort + u32 index).
 */
function skipComponentSortWithIndex(bytes: Buffer | Uint8Array, offset: number): number {
    const sortLen = skipComponentSort(bytes, offset);
    const idx = readLeb128(bytes, offset + sortLen);
    return sortLen + idx.bytesRead;
}

/**
 * Skips an `externDescription` encoding.  The encoding is:
 *   0x00 0x11 u32           — CoreType
 *   0x01 u32                — Type
 *   0x02 valueBound         — Value
 *   0x03 typeBound          — TypeBound
 *   0x04 u32                — Component
 *   0x05 u32                — Instance
 */
function skipExternDescription(bytes: Buffer | Uint8Array, offset: number): number {
    const kind = bytes[offset];
    let off = offset + 1;
    switch (kind) {
        case 0x00: // CoreType: 0x11 byte + u32
            off++; // 0x11
            off += readLeb128(bytes, off).bytesRead;
            break;
        case 0x01: // Type: u32
        case 0x04: // Component: u32
        case 0x05: // Instance: u32
            off += readLeb128(bytes, off).bytesRead;
            break;
        case 0x02: { // ValueBound
            const vbKind = bytes[off];
            off++;
            if (vbKind === 0x00) {
                off += readLeb128(bytes, off).bytesRead; // u32
            } else {
                // 0x01 = componentValueType — skip it
                off += skipComponentValueType(bytes, off);
            }
            break;
        }
        case 0x03: { // TypeBound
            const tbKind = bytes[off];
            off++;
            if (tbKind === 0x00) {
                off += readLeb128(bytes, off).bytesRead; // u32
            }
            // 0x01 = SubResource — nothing more to read
            break;
        }
        default:
            // Unknown — bail out by returning what we have
            break;
    }
    return off - offset;
}

/**
 * Skips a `componentValueType` encoding (primitive byte or index reference).
 */
function skipComponentValueType(bytes: Buffer | Uint8Array, offset: number): number {
    const b = bytes[offset];
    // Primitive types are single bytes >= 0x40 in the component value type space.
    // Index references are LEB128-encoded.
    if (b >= 0x40) {
        return 1; // single-byte primitive
    }
    return readLeb128(bytes, offset).bytesRead;
}

/**
 * Parses component export names from a Wasm component binary.
 * Reads section ID 11 (component export) and extracts the export
 * name strings.
 */
function parseComponentExportNames(bytes: Buffer | Uint8Array): string[] {
    const names: string[] = [];
    const COMPONENT_EXPORT_SECTION_ID = 11;

    for (const section of iterateComponentSections(bytes)) {
        if (section.id !== COMPONENT_EXPORT_SECTION_ID) {continue;}

        let off = section.contentOffset;
        const sectionEnd = section.contentOffset + section.contentSize;

        // Read vector count
        const count = readLeb128(bytes, off);
        off += count.bytesRead;

        for (let i = 0; i < count.value && off < sectionEnd; i++) {
            try {
                // Each export record starts with a discriminant byte
                const discriminant = bytes[off];
                off++;

                // Read export name
                const name = readWasmString(bytes, off);
                off += name.bytesRead;
                const exportName = name.value;

                if (discriminant === 0x01) {
                    // Version string follows
                    const version = readWasmString(bytes, off);
                    off += version.bytesRead;
                }

                names.push(exportName);

                // Skip sortIndex (componentSort + u32 index)
                off += skipComponentSortWithIndex(bytes, off);

                // Skip optional extern description: 0x00 = none, otherwise full externDescription
                const edFlag = bytes[off];
                off++;
                if (edFlag !== 0x00) {
                    off += skipExternDescription(bytes, off);
                }
            } catch {
                break; // Bail out on parse errors
            }
        }
    }
    return names;
}

/**
 * Parses component import names from a Wasm component binary.
 * Reads section ID 10 (component import) and extracts the import
 * name strings.
 */
function parseComponentImportNames(bytes: Buffer | Uint8Array): string[] {
    const names: string[] = [];
    const COMPONENT_IMPORT_SECTION_ID = 10;

    for (const section of iterateComponentSections(bytes)) {
        if (section.id !== COMPONENT_IMPORT_SECTION_ID) {continue;}

        let off = section.contentOffset;
        const sectionEnd = section.contentOffset + section.contentSize;

        // Read vector count
        const count = readLeb128(bytes, off);
        off += count.bytesRead;

        for (let i = 0; i < count.value && off < sectionEnd; i++) {
            try {
                // Each import record starts with a discriminant byte
                const discriminant = bytes[off];
                off++;

                // Read import name
                const name = readWasmString(bytes, off);
                off += name.bytesRead;

                let importName = name.value;

                if (discriminant === 0x01) {
                    // Version string follows — append it to form the full name
                    const version = readWasmString(bytes, off);
                    off += version.bytesRead;
                    if (version.value) {
                        importName = `${importName}@${version.value}`;
                    }
                }

                names.push(importName);

                // Skip extern description
                off += skipExternDescription(bytes, off);
            } catch {
                break;
            }
        }
    }
    return names;
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

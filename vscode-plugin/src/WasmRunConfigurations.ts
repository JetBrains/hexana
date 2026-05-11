import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { execFileSync } from "child_process";
import type * as ExtensionLib from "./extension-lib";

/**
 * Node.js implementation of the FileSystemAccess interface expected by
 * the Kotlin/JS dependency resolver.
 */
export const nodeFileSystemAccess: ExtensionLib.FileSystemAccess = {
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

// Kotlin/JS extension library — provides binary analysis, dependency resolution,
// and shell utilities backed by the shared wasmParser / plugins-shared modules.
// eslint-disable-next-line @typescript-eslint/no-var-requires
export const extensionLib: typeof ExtensionLib | null = (() => {
    try {
        const mod = require("../extension-lib/build/kotlin-webpack/js/productionExecutable/extension-lib.js");
        return mod.hexanaExtensionLib.org.jetbrains.hexana.vscode.lib;
    } catch {
        console.warn("[Hexana] extension-lib not found — falling back to built-in helpers");
        return null;
    }
})();

/**
 * Lightweight document interface exposing the fields that run configurations
 * need from the editor document.
 */
export interface RunDocument {
    readonly uri: vscode.Uri;
    readonly displayName?: string;
}

/**
 * Node.js implementation of the JsStubFileWriter interface that writes
 * temporary stub files to the OS temp directory.
 */
const nodeStubWriter: ExtensionLib.JsStubFileWriter = {
    createTempDir(prefix: string): string {
        return fs.mkdtempSync(path.join(os.tmpdir(), `${prefix}-`));
    },
    writeFile(dir: string, fileName: string, bytes: Int8Array): string {
        const filePath = path.join(dir, fileName);
        fs.writeFileSync(filePath, Buffer.from(bytes));
        return filePath;
    },
};

/**
 * Node.js implementation of the JsProcessRunner interface that executes
 * CLI tools synchronously via child_process.
 */
const nodeProcessRunner: ExtensionLib.JsProcessRunner = {
    exec(command: string, args: string[]): string {
        try {
            execFileSync(command, args, {
                timeout: 30000,
                stdio: ["pipe", "pipe", "pipe"],
            });
            return "";
        } catch (e: any) {
            const stderr = e.stderr ? e.stderr.toString().trim() : "";
            return stderr || e.message || "unknown error";
        }
    },
};

/**
 * Node.js implementation of the JsToolResolver interface that resolves
 * CLI tool paths by checking common locations and the system PATH.
 */
const nodeToolResolver: ExtensionLib.JsToolResolver = {
    resolveToolPath(toolName: string): string | null {
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
        return null;
    },
    fileExists(p: string): boolean {
        return fs.existsSync(p);
    },
};

/**
 * Returns the currently selected Wasm runtime from settings.
 * Returns `"ask"` when the user wants to see a picker every time.
 */
export function getSelectedRuntime(): "wasmtime" | "wamr" | "graalwasm" | "ask" {
    const config = vscode.workspace.getConfiguration("hexana");
    const runtime = config.get<string>("wasmRuntime", "ask").trim();
    if (runtime === "wamr" || runtime === "graalwasm" || runtime === "ask") {
        return runtime;
    }
    return "wasmtime";
}

/**
 * Shows a VS Code QuickPick to let the user choose a runtime.
 *
 * When [isComponent] is true, only runtimes that support the Component
 * Model are offered. The picker also includes a "Configure Runtimes…"
 * item that opens the Hexana settings page.
 *
 * Returns the chosen runtime identifier, or `undefined` if the user
 * dismissed the picker.
 */
export async function showRuntimePicker(
    isComponent: boolean = false,
): Promise<"wasmtime" | "wamr" | "graalwasm" | undefined> {
    interface RuntimeItem extends vscode.QuickPickItem {
        runtime?: "wasmtime" | "wamr" | "graalwasm";
        isConfigure?: boolean;
    }

    const items: RuntimeItem[] = [];

    items.push({
        label: "$(play) Run with Wasmtime",
        runtime: "wasmtime",
    });
    if (!isComponent) {
        items.push({
            label: "$(play) Run with WAMR",
            runtime: "wamr",
        });
        items.push({
            label: "$(play) Run with GraalWasm",
            runtime: "graalwasm",
        });
    }
    // If there is only one applicable runtime, skip the picker and run directly
    if (items.length === 1) {
        return items[0].runtime;
    }

    items.push({
        label: "$(gear) Configure Runtimes\u2026",
        isConfigure: true,
    });

    const picked = await vscode.window.showQuickPick<RuntimeItem>(items, {
        title: "Select Wasm Runtime",
        placeHolder: "Choose a runtime to run the module",
    });

    if (!picked) {
        return undefined;
    }
    if (picked.isConfigure) {
        await vscode.commands.executeCommand(
            "workbench.action.openSettings",
            "hexana.wasmRuntime",
        );
        return undefined;
    }
    return picked.runtime;
}

/**
 * Returns the executable command for the given runtime, respecting
 * the corresponding `hexana.*` path setting.  Shows an error and
 * returns `undefined` when the runtime cannot be resolved.
 */
export function getRuntimeCommand(runtime: string, isWindows: boolean): string | undefined {
    if (!extensionLib) {
        return undefined;
    }

    const config = vscode.workspace.getConfiguration("hexana");
    const wasmtimePath = config.get<string>("wasmtimePath", "");
    const wamrPath = config.get<string>("wamrPath", "");
    const graalVmHome = resolveGraalVmHome();

    const result = extensionLib.resolveRuntimeExecutable(
        runtime, isWindows, wasmtimePath, wamrPath, graalVmHome,
    );

    if (result === null) {
        vscode.window.showErrorMessage(
            "Cannot run: GraalVM home not configured. " +
            "Set 'hexana.graalVmHome' in settings, or set the GRAALVM_HOME or JAVA_HOME environment variable.",
        );
        return undefined;
    }
    return result;
}

/**
 * Resolves the GraalVM home directory from settings or environment
 * variables.  Returns an empty string when no valid path is found.
 */
function resolveGraalVmHome(): string {
    const config = vscode.workspace.getConfiguration("hexana");
    const custom = config.get<string>("graalVmHome", "").trim();
    if (custom) {
        return custom;
    }
    return process.env.GRAALVM_HOME?.trim() || process.env.JAVA_HOME?.trim() || "";
}

/**
 * Reads the raw bytes of a wasm file, returning `null` on failure.
 */
function readWasmBytes(filePath: string): Int8Array | null {
    try {
        const buf = fs.readFileSync(filePath);
        return new Int8Array(buf.buffer, buf.byteOffset, buf.byteLength);
    } catch {
        return null;
    }
}

/**
 * Builds a list of directories to scan for `.wasm` dependency files.
 * Uses the workspace folder and walks up from the wasm file to find
 * a likely workspace root (directory containing `Cargo.toml`,
 * `package.json`, or `.git`).
 */
function buildScanRoots(wasmFilePath: string): string[] {
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

/**
 * Handles a `run-export` message from the webview by executing the Wasm
 * module in a VS Code terminal using the configured runtime.
 *
 * The runtime is selected via the `hexana.wasmRuntime` setting (default:
 * `wasmtime`). When the setting is `"ask"`, a QuickPick is shown so
 * the user can choose a runtime before each run. When the webview sends
 * component import names and the active runtime supports components,
 * dependency resolution and composition are attempted before launch.
 */
export async function runExport(
    document: RunDocument,
    message: {
        exportName: string;
        invokeArgs: string;
        programArgs: string;
        isComponent: boolean;
        hasImports?: boolean;
        stubs?: Array<{ moduleName: string; wasmBytes: number[] }>;
        componentImports?: string[];
    },
): Promise<void> {
    if (!extensionLib) {
        vscode.window.showErrorMessage("Cannot run: extension library is not available.");
        return;
    }

    const filePath = document.uri.fsPath;
    if (!filePath) {
        vscode.window.showErrorMessage("Cannot run: file path is not available for in-memory modules.");
        return;
    }

    const isComponent = message.isComponent && message.componentImports && message.componentImports.length > 0;

    let runtime = getSelectedRuntime();
    if (runtime === "ask") {
        const picked = await showRuntimePicker(!!isComponent);
        if (!picked) {
            return; // user dismissed the picker
        }
        runtime = picked;
    }

    // Component-model binaries require Wasmtime
    if (isComponent) {
        if (runtime !== "wasmtime") {
            vscode.window.showWarningMessage(
                `Component-model binaries are only supported by Wasmtime. ` +
                `The current runtime is '${runtime}'. Falling back to Wasmtime.`,
            );
        }
        runComponentExport(document, message);
        return;
    }

    const isWindows = os.platform() === "win32";
    const wasmBytes = readWasmBytes(filePath);

    // Build the command line using the unified Kotlin dispatcher
    const cmdLine = extensionLib.buildRuntimeCommandLine(
        runtime,
        filePath,
        wasmBytes,
        message.exportName,
        message.invokeArgs,
        message.programArgs,
        null,
        nodeStubWriter,
    );

    if (!cmdLine) {
        return; // error already shown
    }

    const runtimeCmd = getRuntimeCommand(runtime, isWindows);
    if (!runtimeCmd) {
        return; // error already shown
    }

    const exportName = message.exportName || path.basename(filePath, ".wasm");
    const terminalName = `Wasm: ${exportName}`;

    const allArgs = [...cmdLine.arguments];
    const terminal = vscode.window.createTerminal({
        name: terminalName,
        cwd: cmdLine.workingDirectory ?? undefined,
    });
    terminal.show();
    terminal.sendText(
        `${extensionLib.shellEscape(runtimeCmd, isWindows)} ${allArgs.map((a: string) => extensionLib!.shellEscape(a, isWindows)).join(" ")}`,
    );
}

/**
 * Handles running a component-model export by first resolving
 * dependencies from the filesystem (including transitive deps),
 * composing them using `wasm-tools compose` or `wac plug`, and
 * then running the result via Wasmtime (the only runtime that
 * supports the Component Model).
 */
function runComponentExport(
    document: RunDocument,
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
    const scanRoots = buildScanRoots(filePath);
    const resolution = extensionLib.resolveAllDependencies(
        componentImports, scanRoots, filePath, nodeFileSystemAccess,
    );
    const depNames: string[] = [];
    const depPaths: string[] = [];
    for (const dep of resolution.resolved) {
        depNames.push(dep.importName);
        depPaths.push(dep.filePath);
    }

    let effectiveWasmPath = filePath;

    if (depNames.length > 0) {
        const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "hexana-wac-compose-"));
        const composeResult = extensionLib.composeComponents(
            filePath, depNames, depPaths, tempDir,
            nodeProcessRunner, nodeToolResolver, nodeFileSystemAccess,
        );
        if (composeResult.composed && composeResult.wasmPath) {
            effectiveWasmPath = composeResult.wasmPath;
        } else if (composeResult.error) {
            vscode.window.showWarningMessage(
                `Component composition failed: ${composeResult.error}. Running original file.`,
            );
        }
    }

    // Component-model runs always use Wasmtime
    const cmdLine = extensionLib.buildWasmtimeCommandLine(
        filePath,
        null,
        message.programArgs || "",
        message.invokeArgs || "",
        effectiveWasmPath !== filePath ? effectiveWasmPath : null,
        null,
    );

    const exportName = message.exportName || path.basename(filePath, ".wasm");
    const terminalName = `Wasm: ${exportName}`;

    const isWindows = os.platform() === "win32";
    const wasmtimeCmd = getRuntimeCommand("wasmtime", isWindows)!;
    const terminal = vscode.window.createTerminal({ name: terminalName });
    terminal.show();
    terminal.sendText(
        `${extensionLib.shellEscape(wasmtimeCmd, isWindows)} ${cmdLine.arguments.map((a: string) => extensionLib!.shellEscape(a, isWindows)).join(" ")}`,
    );
}

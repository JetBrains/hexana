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
 * Result of the runtime picker indicating both the chosen runtime and
 * whether the user selected a run or debug action.
 */
export interface RuntimePickerResult {
    runtime: "wasmtime" | "wamr" | "graalwasm";
    action: "run" | "debug";
}

/**
 * Shows a VS Code QuickPick to let the user choose a runtime and action.
 *
 * When [isComponent] is true, only runtimes that support the Component
 * Model are offered for running (Wasmtime only); debugging is available
 * via Wasmtime for both core and component modules, while WAMR debugging
 * is limited to core modules. The picker also includes a "Configure
 * Runtimes…" item that opens the Hexana settings page.
 *
 * Returns the chosen runtime and action, or `undefined` if the user
 * dismissed the picker.
 */
export async function showRuntimePicker(
    isComponent: boolean = false,
): Promise<RuntimePickerResult | undefined> {
    interface RuntimeItem extends vscode.QuickPickItem {
        runtime?: "wasmtime" | "wamr" | "graalwasm";
        action?: "run" | "debug";
        isConfigure?: boolean;
    }

    const items: RuntimeItem[] = [];

    items.push({
        label: "$(play) Run with Wasmtime",
        runtime: "wasmtime",
        action: "run",
    });
    if (!isComponent) {
        items.push({
            label: "$(play) Run with WAMR",
            runtime: "wamr",
            action: "run",
        });
        items.push({
            label: "$(play) Run with GraalWasm",
            runtime: "graalwasm",
            action: "run",
        });
    }

    // Debug options — Wasmtime supports debugging for both core and
    // component modules; WAMR only supports core modules.
    items.push({
        label: "$(bug) Debug with Wasmtime",
        runtime: "wasmtime",
        action: "debug",
    });
    if (!isComponent) {
        items.push({
            label: "$(bug) Debug with WAMR",
            runtime: "wamr",
            action: "debug",
        });
    }

    // If there is only one applicable item, use it directly
    if (items.length === 1) {
        return { runtime: items[0].runtime!, action: items[0].action! };
    }

    items.push({
        label: "$(gear) Configure Runtimes\u2026",
        isConfigure: true,
    });

    const picked = await vscode.window.showQuickPick<RuntimeItem>(items, {
        title: "Select Wasm Runtime",
        placeHolder: "Choose a runtime to run or debug the module",
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
    return { runtime: picked.runtime!, action: picked.action! };
}

/**
 * Returns the executable command for the given runtime, respecting
 * the corresponding `hexana.*` path setting.  Shows an error and
 * returns `undefined` when the runtime cannot be resolved.
 */
export function getRuntimeCommand(runtime: string, isWindows: boolean): string | undefined {
    if (!extensionLib) {
        vscode.window.showErrorMessage("Cannot resolve runtime: extension library is not available.");
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
            "Cannot run: no GraalVM with GraalWasm found. " +
            "Install a GraalVM JDK that includes the 'wasm' launcher, then either " +
            "set 'hexana.graalVmHome' in settings, set the GRAALVM_HOME environment variable, " +
            "or place it in a standard JVM directory so the extension can detect it automatically.",
        );
        return undefined;
    }
    return result;
}

/**
 * Resolves the GraalVM home directory from settings, environment
 * variables, or by scanning common JVM installation directories.
 *
 * Resolution order:
 * 1. `hexana.graalVmHome` VS Code setting
 * 2. `GRAALVM_HOME` environment variable
 * 3. `JAVA_HOME` environment variable (only if it contains `bin/wasm`)
 * 4. Scan well-known JVM installation directories for a JVM that
 *    ships the GraalWasm `wasm` launcher (`bin/wasm` or `bin/wasm.exe`)
 *
 * Returns an empty string when no valid path is found.
 */
function resolveGraalVmHome(): string {
    const config = vscode.workspace.getConfiguration("hexana");
    const custom = config.get<string>("graalVmHome", "").trim();
    if (custom) {
        return custom;
    }

    const graalVmHomeEnv = process.env.GRAALVM_HOME?.trim() || "";
    if (graalVmHomeEnv) {
        return graalVmHomeEnv;
    }

    const javaHome = process.env.JAVA_HOME?.trim() || "";
    if (javaHome && hasGraalWasmLauncher(javaHome)) {
        return javaHome;
    }

    return discoverGraalVmHome() || "";
}

/**
 * Checks whether the given JVM home directory contains the GraalWasm
 * `wasm` launcher under `bin/`.
 */
function hasGraalWasmLauncher(jvmHome: string): boolean {
    const isWindows = os.platform() === "win32";
    const exeName = isWindows ? "wasm.exe" : "wasm";
    return fs.existsSync(path.join(jvmHome, "bin", exeName));
}

/**
 * Scans well-known JVM installation directories for a JVM that ships
 * the GraalWasm `wasm` launcher.
 *
 * Checked locations by platform:
 * - **macOS**: `/Library/Java/JavaVirtualMachines`, `~/Library/Java/JavaVirtualMachines`,
 *   `~/.sdkman/candidates/java`, `~/.jenv/versions`, `~/.jabba/jdk`
 * - **Linux**: `/usr/lib/jvm`, `/usr/java`, `~/.sdkman/candidates/java`,
 *   `~/.jenv/versions`, `~/.jabba/jdk`
 * - **Windows**: `C:\Program Files\Java`, `C:\Program Files\GraalVM`,
 *   `~\.sdkman\candidates\java`, `~\.jenv\versions`, `~\.jabba\jdk`
 *
 * On macOS the effective JVM home is `<dir>/Contents/Home` (the standard
 * JDK bundle layout); on other platforms it is the directory itself.
 *
 * Returns the first JVM home that contains `bin/wasm`, or `null` when
 * none is found.
 */
function discoverGraalVmHome(): string | null {
    const platform = os.platform();
    const home = os.homedir();
    const isWindows = platform === "win32";
    const isMacOS = platform === "darwin";

    const parentDirs: string[] = [];
    if (isMacOS) {
        parentDirs.push(
            "/Library/Java/JavaVirtualMachines",
            path.join(home, "Library", "Java", "JavaVirtualMachines"),
            path.join(home, ".sdkman", "candidates", "java"),
            path.join(home, ".jenv", "versions"),
            path.join(home, ".jabba", "jdk"),
        );
    } else if (isWindows) {
        parentDirs.push(
            "C:\\Program Files\\Java",
            "C:\\Program Files\\GraalVM",
            path.join(home, ".sdkman", "candidates", "java"),
            path.join(home, ".jenv", "versions"),
            path.join(home, ".jabba", "jdk"),
        );
    } else {
        // Linux and other Unix-like systems
        parentDirs.push(
            "/usr/lib/jvm",
            "/usr/java",
            path.join(home, ".sdkman", "candidates", "java"),
            path.join(home, ".jenv", "versions"),
            path.join(home, ".jabba", "jdk"),
        );
    }

    for (const parentDir of parentDirs) {
        let entries: fs.Dirent[];
        try {
            entries = fs.readdirSync(parentDir, { withFileTypes: true });
        } catch {
            continue; // directory does not exist or is not readable
        }
        for (const entry of entries) {
            let isDir = entry.isDirectory();
            if (!isDir && entry.isSymbolicLink()) {
                try {
                    isDir = fs.statSync(path.join(parentDir, entry.name)).isDirectory();
                } catch {
                    continue;
                }
            }
            if (!isDir) {
                continue;
            }
            // macOS JDK bundles use <name>.jdk/Contents/Home layout
            const jvmHome = isMacOS
                ? path.join(parentDir, entry.name, "Contents", "Home")
                : path.join(parentDir, entry.name);
            if (hasGraalWasmLauncher(jvmHome)) {
                return jvmHome;
            }
        }
    }
    return null;
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
    let action: "run" | "debug" = "run";
    if (runtime === "ask") {
        const picked = await showRuntimePicker(!!isComponent);
        if (!picked) {
            return; // user dismissed the picker
        }
        runtime = picked.runtime;
        action = picked.action;
    }

    // Component-model binaries require Wasmtime
    if (isComponent) {
        if (runtime !== "wasmtime") {
            vscode.window.showWarningMessage(
                `Component-model binaries are only supported by Wasmtime. ` +
                `The current runtime is '${runtime}'. Falling back to Wasmtime.`,
            );
        }
        if (action === "debug") {
            const componentImports = message.componentImports || [];
            const effectiveWasmPath = composeComponentDependencies(filePath, componentImports);
            await launchDebugSession(
                filePath,
                "wasmtime",
                message.programArgs ?? "",
                message.exportName ?? "",
                message.invokeArgs ?? "",
                effectiveWasmPath !== filePath ? effectiveWasmPath : undefined,
            );
            return;
        }
        runComponentExport(document, message);
        return;
    }

    // Launch a debug session instead of a terminal when the user chose debug
    if (action === "debug") {
        await launchDebugSession(
            filePath,
            runtime,
            message.programArgs ?? "",
            message.exportName ?? "",
            message.invokeArgs ?? "",
        );
        return;
    }

    const isWindows = os.platform() === "win32";
    const wasmBytes = readWasmBytes(filePath);

    // Build the command line using the unified Kotlin dispatcher
    const cmdLine = extensionLib.buildRuntimeCommandLine(
        runtime,
        filePath,
        wasmBytes,
        message.exportName ?? "",
        message.invokeArgs ?? "",
        message.programArgs ?? "",
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
 * Launches a VS Code debug session for the given `.wasm` file using
 * the registered `wasm` debug configuration type.
 *
 * This creates an ad-hoc launch configuration and delegates to the
 * `WasmDebugAdapter` infrastructure (configuration provider, adapter
 * factory, and tracker) already registered in `extension.ts`.
 *
 * Only Wasmtime and WAMR are supported for debugging. If the caller
 * passes a different runtime, an error is shown and no session starts.
 */
async function launchDebugSession(
    wasmFilePath: string,
    runtime: string,
    programArgs: string,
    exportName: string = "",
    invokeArgs: string = "",
    effectiveWasmPath?: string,
): Promise<void> {
    if (runtime !== "wasmtime" && runtime !== "wamr") {
        vscode.window.showErrorMessage(
            `Debugging is only supported for Wasmtime and WAMR runtimes, not '${runtime}'.`,
        );
        return;
    }

    const debugConfig: vscode.DebugConfiguration = {
        type: "wasm",
        request: "launch",
        name: `Debug Wasm (${runtime === "wasmtime" ? "Wasmtime" : "WAMR"})`,
        wasmFile: wasmFilePath,
        runtime,
        programArgs,
        exportName,
        invokeArgs,
        ...(effectiveWasmPath ? { effectiveWasmPath } : {}),
    };

    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    await vscode.debug.startDebugging(workspaceFolder, debugConfig);
}

/**
 * Resolves component dependencies from the filesystem and composes
 * them using `wasm-tools compose` or `wac plug`.
 *
 * Returns the path to the composed `.wasm` file when composition
 * succeeds, or the original [filePath] when no dependencies are
 * found or composition fails (a warning is shown to the user in
 * the latter case).
 */
function composeComponentDependencies(
    filePath: string,
    componentImports: string[],
): string {
    if (!extensionLib) {
        return filePath;
    }

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

    if (depNames.length === 0) {
        return filePath;
    }

    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "hexana-wac-compose-"));
    const composeResult = extensionLib.composeComponents(
        filePath, depNames, depPaths, tempDir,
        nodeProcessRunner, nodeToolResolver, nodeFileSystemAccess,
    );
    if (composeResult.composed && composeResult.wasmPath) {
        return composeResult.wasmPath;
    }
    if (composeResult.error) {
        vscode.window.showWarningMessage(
            `Component composition failed: ${composeResult.error}. Running original file.`,
        );
    }
    return filePath;
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
    const effectiveWasmPath = composeComponentDependencies(filePath, componentImports);

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
    const wasmtimeCmd = getRuntimeCommand("wasmtime", isWindows);
    if (!wasmtimeCmd) {
        return; // error already shown
    }
    const terminal = vscode.window.createTerminal({ name: terminalName });
    terminal.show();
    terminal.sendText(
        `${extensionLib.shellEscape(wasmtimeCmd, isWindows)} ${cmdLine.arguments.map((a: string) => extensionLib!.shellEscape(a, isWindows)).join(" ")}`,
    );
}

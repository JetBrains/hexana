import * as fs from "fs";
import * as http from "http";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { execFileSync, spawnSync } from "child_process";
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
export function getSelectedRuntime(): "wasmtime" | "wamr" | "graalwasm" | "wazero" | "browser" | "nodejs" | "ask" {
    const config = vscode.workspace.getConfiguration("hexana");
    const runtime = config.get<string>("wasmRuntime", "ask").trim();
    if (runtime === "wamr" || runtime === "graalwasm" || runtime === "wazero" || runtime === "browser" || runtime === "nodejs" || runtime === "ask") {
        return runtime;
    }
    return "wasmtime";
}

/**
 * Result of the runtime picker indicating both the chosen runtime and
 * whether the user selected a run or debug action.
 */
export interface RuntimePickerResult {
    runtime: "wasmtime" | "wamr" | "graalwasm" | "wazero" | "browser" | "nodejs";
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
        runtime?: "wasmtime" | "wamr" | "graalwasm" | "wazero" | "browser" | "nodejs";
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
        items.push({
            label: "$(play) Run with Wazero",
            runtime: "wazero",
            action: "run",
        });
    }
    items.push({
        label: "$(globe) Run in Default Browser",
        runtime: "browser",
        action: "run",
    });
    if (!isComponent) {
        items.push({
            label: "$(play) Run with Node.js",
            runtime: "nodejs",
            action: "run",
        });
    }

    // Debug options — Wasmtime supports debugging for both core and
    // component modules; WAMR only supports core modules.
    // Node.js and Browser use CDP (Chrome DevTools Protocol) debugging.
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
        items.push({
            label: "$(bug) Debug with Node.js (CDP)",
            runtime: "nodejs",
            action: "debug",
        });
    }
    items.push({
        label: "$(bug) Debug with Chrome (CDP)",
        runtime: "browser",
        action: "debug",
    });

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
    const wazeroPath = config.get<string>("wazeroPath", "");
    const graalVmHome = resolveGraalVmHome();
    const nodePath = config.get<string>("nodePath", "");

    const result = extensionLib.resolveRuntimeExecutable(
        runtime, isWindows, wasmtimePath, wamrPath, wazeroPath, graalVmHome, nodePath,
    );

    if (result === null) {
        vscode.window.showErrorMessage(
            "Cannot run: no GraalVM JDK found for GraalWasm. " +
            "Install a GraalVM JDK, then either " +
            "set 'hexana.graalVmHome' in settings, set the GRAALVM_HOME or JAVA_HOME " +
            "environment variable, or place it in a standard JVM directory so the " +
            "extension can detect it automatically.",
        );
        return undefined;
    }
    return result;
}

/**
 * Resolves the GraalVM JDK home directory for GraalWasm from settings,
 * environment variables, or by scanning common JVM installation
 * directories.
 *
 * The extension uses a thin Java wrapper (`HexanaGraalWasmRunner`)
 * that is compiled on first use and executed via `java`. The JDK
 * must be a GraalVM distribution (the Polyglot/WebAssembly engine
 * is on its module path).
 *
 * Resolution order:
 * 1. `hexana.graalVmHome` VS Code setting
 * 2. `GRAALVM_HOME` environment variable
 * 3. `JAVA_HOME` environment variable (if it contains `bin/java`)
 * 4. Scan well-known JVM installation directories for a JDK that
 *    contains `bin/java` (or `bin/java.exe` on Windows)
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
    if (javaHome && hasJavaExecutable(javaHome)) {
        return javaHome;
    }

    return discoverJdkHome() || "";
}

/**
 * Checks whether the given JVM home directory contains a `java`
 * executable under `bin/`.
 */
function hasJavaExecutable(jvmHome: string): boolean {
    const isWindows = os.platform() === "win32";
    const exeName = isWindows ? "java.exe" : "java";
    return fs.existsSync(path.join(jvmHome, "bin", exeName));
}

/**
 * Scans well-known JVM installation directories for a JDK that
 * contains a `java` executable.
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
 * Returns the first JVM home that contains `bin/java`, or `null` when
 * none is found.
 */
function discoverJdkHome(): string | null {
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
            if (hasJavaExecutable(jvmHome)) {
                return jvmHome;
            }
        }
    }
    return null;
}

// ---------------------------------------------------------------------------
// GraalVM runner helpers
// ---------------------------------------------------------------------------

/** Fully qualified class name of the thin GraalWasm wrapper. */
const GRAAL_RUNNER_CLASS = "org.jetbrains.hexana.runner.HexanaGraalWasmRunner";

/**
 * Returns the path to the `graalvm-runner/` directory bundled with
 * the extension.  The directory contains `src/HexanaGraalWasmRunner.java`.
 */
function resolveGraalRunnerDir(): string {
    // __dirname points to `out/` after TypeScript compilation;
    // `graalvm-runner/` is a sibling directory at the extension root.
    return path.join(__dirname, "..", "graalvm-runner");
}

/**
 * Runs `java -version` from the given `java` executable and extracts
 * the GraalVM / JDK version string.
 */
function detectGraalVmVersion(javaExe: string): string {
    // java -version traditionally writes to stderr, so we use spawnSync
    // to reliably capture both stdout and stderr.
    const result = spawnSync(javaExe, ["-version"], {
        timeout: 15000,
        encoding: "utf-8",
    });
    const output = ((result.stdout as string) || "") + "\n" + ((result.stderr as string) || "");

    // Look for a GraalVM-specific line first
    const graalLine = output.split("\n").find(l => l.includes("GraalVM"));
    if (graalLine) {
        const m = graalLine.match(/(\d+\.\d+\.\d+)/);
        if (m) { return m[1]; }
    }

    // Fall back to the JDK version from the first line
    const versionMatch = output.match(/"(\d+[\d.]*)"/);
    if (versionMatch) { return versionMatch[1]; }

    throw new Error(`Cannot detect GraalVM/JDK version from:\n${output}`);
}

/**
 * Downloads a file from the given URL synchronously by spawning a
 * child `node` process that performs the async download.
 */
function downloadFileSync(url: string, destPath: string): void {
    // Use curl/wget if available, otherwise fall back to node
    const isWindows = os.platform() === "win32";
    try {
        if (!isWindows) {
            execFileSync("curl", ["-fsSL", "-o", destPath, url], {
                timeout: 120000,
                stdio: ["pipe", "pipe", "pipe"],
            });
            return;
        }
    } catch {
        // curl not available, fall through
    }

    // Fallback: use node inline script
    const script = `
        const https = require("https");
        const http = require("http");
        const fs = require("fs");
        function download(url, dest) {
            return new Promise((resolve, reject) => {
                const get = url.startsWith("https") ? https.get : http.get;
                get(url, (res) => {
                    if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                        download(res.headers.location, dest).then(resolve, reject);
                        return;
                    }
                    if (res.statusCode !== 200) { reject(new Error("HTTP " + res.statusCode)); return; }
                    const file = fs.createWriteStream(dest);
                    res.pipe(file);
                    file.on("finish", () => { file.close(); resolve(); });
                    file.on("error", reject);
                }).on("error", reject);
            });
        }
        download(process.argv[1], process.argv[2]).catch(e => { console.error(e); process.exit(1); });
    `;
    execFileSync(process.execPath, ["-e", script, url, destPath], {
        timeout: 120000,
        stdio: ["pipe", "pipe", "pipe"],
    });
}

/**
 * Detects the GraalVM version from `java -version` output and downloads
 * the required Polyglot JARs from Maven Central if not already cached.
 *
 * @param javaExe  absolute path to the `java` executable
 * @returns list of absolute paths to the downloaded JAR files
 */
function resolvePolyglotJars(javaExe: string): string[] {
    const version = detectGraalVmVersion(javaExe);
    const cacheDir = path.join(os.tmpdir(), "hexana-graalwasm-runner", "libs", version);
    fs.mkdirSync(cacheDir, { recursive: true });

    const artifacts = [
        `org.graalvm.polyglot:polyglot:${version}`,
        `org.graalvm.wasm:wasm-language:${version}`,
        `org.graalvm.sdk:collections:${version}`,
        `org.graalvm.sdk:nativeimage:${version}`,
        `org.graalvm.sdk:word:${version}`,
        `org.graalvm.truffle:truffle-api:${version}`,
        `org.graalvm.truffle:truffle-compiler:${version}`,
        `org.graalvm.truffle:truffle-runtime:${version}`,
    ];

    return artifacts.map(coords => {
        const parts = coords.split(":");
        const groupPath = parts[0].replace(/\./g, "/");
        const artifactId = parts[1];
        const ver = parts[2];
        const fileName = `${artifactId}-${ver}.jar`;
        const jarPath = path.join(cacheDir, fileName);

        if (!fs.existsSync(jarPath)) {
            const url = `https://repo1.maven.org/maven2/${groupPath}/${artifactId}/${ver}/${fileName}`;
            try {
                downloadFileSync(url, jarPath);
            } catch (e: any) {
                throw new Error(
                    `Failed to download ${url}: ${e.message}\n` +
                    `Make sure you have internet access or pre-cache the GraalVM Polyglot JARs.`,
                );
            }
        }

        return jarPath;
    });
}

/**
 * Compiles the bundled `HexanaGraalWasmRunner.java` source using
 * `javac` from the given `java` executable path.  The compiled
 * `.class` file is cached under the OS temp directory and reused
 * across runs.
 *
 * @param javaExe      absolute path to the `java` executable (used to
 *                     derive `javac` in the same `bin/` directory)
 * @param polyglotJars paths to the Polyglot JARs needed on the compile classpath
 * @returns the classes output directory, or `null` on failure
 */
function compileRunnerIfNeeded(javaExe: string, polyglotJars: string[]): string | null {
    const cacheDir = path.join(os.tmpdir(), "hexana-graalwasm-runner");
    const classFile = path.join(
        cacheDir, "org", "jetbrains", "hexana", "runner", "HexanaGraalWasmRunner.class",
    );

    // Locate the runner source bundled with the extension
    const sourceFile = path.join(resolveGraalRunnerDir(), "src", "HexanaGraalWasmRunner.java");
    if (!fs.existsSync(sourceFile)) {
        vscode.window.showErrorMessage(
            "HexanaGraalWasmRunner.java not found in extension resources. " +
            "The extension may not have been packaged correctly.",
        );
        return null;
    }

    // Use a hash of the source to invalidate the cache when the runner changes
    const sourceText = fs.readFileSync(sourceFile, "utf-8");
    let sourceHash = 0;
    for (let i = 0; i < sourceText.length; i++) {
        sourceHash = ((sourceHash << 5) - sourceHash + sourceText.charCodeAt(i)) | 0;
    }
    const hashStr = sourceHash.toString();
    const hashFile = path.join(cacheDir, "source.hash");
    if (fs.existsSync(classFile) && fs.existsSync(hashFile) && fs.readFileSync(hashFile, "utf-8") === hashStr) {
        return cacheDir;
    }

    // Derive javac from the same bin/ directory as java
    const binDir = path.dirname(javaExe);
    const isWindows = os.platform() === "win32";
    const javacExe = path.join(binDir, isWindows ? "javac.exe" : "javac");
    if (!fs.existsSync(javacExe)) {
        vscode.window.showErrorMessage(
            `Cannot find javac in ${binDir}. Make sure the configured JDK path is a full JDK (not a JRE).`,
        );
        return null;
    }

    fs.mkdirSync(cacheDir, { recursive: true });

    const javacArgs: string[] = [];
    if (polyglotJars.length > 0) {
        javacArgs.push("-cp", polyglotJars.join(path.delimiter));
    }
    javacArgs.push("-d", cacheDir, sourceFile);

    try {
        execFileSync(javacExe, javacArgs, {
            timeout: 30000,
            stdio: ["pipe", "pipe", "pipe"],
        });
    } catch (e: any) {
        const stderr = e.stderr ? e.stderr.toString().trim() : "";
        vscode.window.showErrorMessage(
            `Failed to compile HexanaGraalWasmRunner.\n${stderr || e.message}`,
        );
        return null;
    }

    fs.writeFileSync(hashFile, hashStr);

    return cacheDir;
}

/**
 * Prepares the extra arguments that must be inserted between the
 * `java` executable and the GraalWasm runner arguments to set up
 * the classpath and runner class.
 *
 * Downloads the required GraalVM Polyglot JARs from Maven Central
 * (cached under the OS temp directory) and builds the full classpath.
 *
 * Returns `["-cp", "<classpath>", "org.jetbrains.hexana.runner.HexanaGraalWasmRunner"]`,
 * or `undefined` on failure.
 */
function prepareGraalRunnerArgs(javaExe: string): string[] | undefined {
    let polyglotJars: string[];
    try {
        polyglotJars = resolvePolyglotJars(javaExe);
    } catch (e: any) {
        vscode.window.showErrorMessage(e.message);
        return undefined;
    }

    const classesDir = compileRunnerIfNeeded(javaExe, polyglotJars);
    if (!classesDir) {
        return undefined; // error already shown
    }

    // Use the JAR directory with a wildcard to keep the command line short.
    // All Polyglot JARs reside in the same cache directory, so `dir/*`
    // tells the JVM to load every JAR in that folder.
    const jarDir = path.dirname(polyglotJars[0]);
    const classpath = [classesDir, path.join(jarDir, "*")].join(path.delimiter);
    return ["--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-cp", classpath, GRAAL_RUNNER_CLASS];
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
        runtime?: string;
        action?: string;
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

    let runtime: "wasmtime" | "wamr" | "graalwasm" | "wazero" | "browser" | "nodejs";
    let action: "run" | "debug";

    // When the webview dialog already collected a runtime and action,
    // use them directly and skip the native VS Code picker.
    if (message.runtime && message.action) {
        const rawRuntime = message.runtime as string;
        runtime = (rawRuntime === "wasmtime" || rawRuntime === "wamr" || rawRuntime === "graalwasm" || rawRuntime === "wazero" || rawRuntime === "browser" || rawRuntime === "nodejs") ? rawRuntime : "wasmtime";
        action = message.action === "debug" ? "debug" : "run";
    } else {
        const configured = getSelectedRuntime();
        if (configured === "ask") {
            const picked = await showRuntimePicker(!!isComponent);
            if (!picked) {
                return; // user dismissed the picker
            }
            runtime = picked.runtime;
            action = picked.action;
        } else {
            runtime = configured;
            action = "run";
        }
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

    // Browser runtime (run only) — serve the module over a local HTTP server and open the default browser
    if (runtime === "browser") {
        await runInBrowser(filePath, message.exportName ?? "", message.programArgs ?? "");
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

    // For GraalWasm, prepend the classpath and runner class to the
    // argument list so the terminal command becomes:
    //   java -cp <classpath> HexanaGraalWasmRunner --stub <mod>=<path> <args>
    let allArgs: string[];
    if (runtime === "graalwasm") {
        const graalRunnerArgs = prepareGraalRunnerArgs(runtimeCmd);
        if (!graalRunnerArgs) {
            return; // error already shown
        }

        // Generate import stub modules and pass them as --stub arguments
        const stubArgs: string[] = [];
        if (wasmBytes && extensionLib) {
            try {
                const stubs = extensionLib.generateImportStubs(wasmBytes);
                if (stubs && stubs.length > 0) {
                    const stubDir = path.join(os.tmpdir(), "hexana-graalwasm-runner", "stubs");
                    fs.mkdirSync(stubDir, { recursive: true });
                    for (const stub of stubs) {
                        const safeName = stub.moduleName.replace(/\//g, "_") + ".wasm";
                        const stubFile = path.join(stubDir, safeName);
                        fs.writeFileSync(stubFile, Buffer.from(stub.wasmBytes));
                        stubArgs.push("--stub", `${stub.moduleName}=${stubFile}`);
                    }
                }
            } catch {
                // Stub generation is best-effort; proceed without stubs
            }
        }

        allArgs = [...graalRunnerArgs, ...stubArgs, ...cmdLine.arguments];
    } else {
        allArgs = [...cmdLine.arguments];
    }

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
 * Wasmtime, WAMR, Node.js, and Browser are supported for debugging.
 * If the caller passes a different runtime, an error is shown and no
 * session starts.
 */
async function launchDebugSession(
    wasmFilePath: string,
    runtime: string,
    programArgs: string,
    exportName: string = "",
    invokeArgs: string = "",
    effectiveWasmPath?: string,
): Promise<void> {
    const supportedRuntimes = ["wasmtime", "wamr", "nodejs", "browser"];
    if (!supportedRuntimes.includes(runtime)) {
        vscode.window.showErrorMessage(
            `Debugging is only supported for Wasmtime, WAMR, Node.js, and Browser runtimes, not '${runtime}'.`,
        );
        return;
    }

    const runtimeLabels: Record<string, string> = {
        wasmtime: "Wasmtime",
        wamr: "WAMR",
        nodejs: "Node.js",
        browser: "Chrome",
    };

    const debugConfig: vscode.DebugConfiguration = {
        type: "wasm",
        request: "launch",
        name: `Debug Wasm (${runtimeLabels[runtime] || runtime})`,
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

/**
 * Active browser-runner HTTP servers keyed by the `.wasm` file path.
 * Tracked so that re-running the same file shuts down the previous
 * server instead of leaking ports.
 */
const activeBrowserServers = new Map<string, http.Server>();

/**
 * Runs a `.wasm` module in the user's default browser by starting a
 * lightweight local HTTP server that serves an HTML page with the
 * WebAssembly runner and the binary itself.
 *
 * Mirrors the IntelliJ `BrowserCommandLineState` implementation.
 */
async function runInBrowser(
    filePath: string,
    exportName: string,
    programArgs: string,
): Promise<void> {
    if (!fs.existsSync(filePath)) {
        vscode.window.showErrorMessage(`Wasm file not found: ${filePath}`);
        return;
    }

    // Shut down a previous server for the same file, if any
    const prev = activeBrowserServers.get(filePath);
    if (prev) {
        prev.close();
        activeBrowserServers.delete(filePath);
    }

    const wasmFileName = path.basename(filePath);
    const effectiveExport = exportName || "_start";
    const wasmBytes = fs.readFileSync(filePath);
    const htmlContent = extensionLib
        ? extensionLib.generateBrowserHtml(wasmFileName, effectiveExport, programArgs)
        : generateBrowserHtmlStubFallback(wasmFileName, effectiveExport, programArgs);

    const server = http.createServer((req, res) => {
        const urlPath = req.url?.split("?")[0] ?? "/";
        if (urlPath === "/" || urlPath === "/index.html") {
            res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
            res.end(htmlContent);
        } else if (urlPath === `/${wasmFileName}`) {
            res.writeHead(200, { "Content-Type": "application/wasm" });
            res.end(wasmBytes);
        } else {
            res.writeHead(404);
            res.end();
        }
    });

    await new Promise<void>((resolve, reject) => {
        server.listen(0, "127.0.0.1", () => resolve());
        server.on("error", reject);
    });

    const addr = server.address();
    if (!addr || typeof addr === "string") {
        vscode.window.showErrorMessage("Failed to start local HTTP server for browser runner.");
        server.close();
        return;
    }

    activeBrowserServers.set(filePath, server);

    const url = `http://127.0.0.1:${addr.port}/`;

    const outputChannel = vscode.window.createOutputChannel(`Wasm Browser: ${effectiveExport}`);
    outputChannel.show(true);
    outputChannel.appendLine(`Serving WebAssembly module at ${url}`);
    outputChannel.appendLine(`Export: ${effectiveExport}`);
    outputChannel.appendLine("Open the browser developer console to see the output.");
    outputChannel.appendLine("Close this output channel or re-run to shut down the server.\n");

    vscode.env.openExternal(vscode.Uri.parse(url));
}

/**
 * Fallback HTML generator used when the Kotlin/JS extension library
 * is not available.  When the library is present, the shared
 * `generateBrowserHtml` (backed by `plugins-shared`) is used instead
 * so that both the IntelliJ plugin and the VS Code extension produce
 * identical output.
 */
function generateBrowserHtmlStubFallback(wasmFileName: string, exportName: string, programArgs: string): string {
    const argsArray = programArgs.trim()
        ? programArgs.trim().split(/\s+/).map(a => `"${a}"`).join(", ")
        : "";

    return `<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>WebAssembly Runner — ${wasmFileName}</title>
    <style>
        body { font-family: system-ui, sans-serif; margin: 2rem; background: #1e1e1e; color: #d4d4d4; }
        h1 { color: #569cd6; font-size: 1.4rem; }
        pre { background: #252526; padding: 1rem; border-radius: 4px; overflow-x: auto; white-space: pre-wrap; }
        .info { color: #9cdcfe; }
        .error { color: #f44747; }
        .success { color: #6a9955; }
    </style>
</head>
<body>
    <h1>WebAssembly Runner</h1>
    <p class="info">Module: <code>${wasmFileName}</code> | Export: <code>${exportName}</code></p>
    <pre id="output"></pre>
    <script>
        const output = document.getElementById('output');
        function log(msg, cls) {
            const span = document.createElement('span');
            span.className = cls || '';
            span.textContent = msg + '\\n';
            output.appendChild(span);
            console.log(msg);
        }

        (async () => {
            try {
                const response = await fetch('${wasmFileName}');
                const bytes = await response.arrayBuffer();

                const module = await WebAssembly.compile(bytes);
                const imports = WebAssembly.Module.imports(module);
                const importObject = {};
                for (const { module: mod, name, kind } of imports) {
                    if (!importObject[mod]) {
                        importObject[mod] = {};
                    }
                    if (kind === 'function') {
                        importObject[mod][name] = (...args) => {
                            log(mod + '.' + name + '(' + args.join(', ') + ')', 'info');
                            return 0;
                        };
                    } else if (kind === 'memory') {
                        importObject[mod][name] = new WebAssembly.Memory({ initial: 256 });
                    } else if (kind === 'table') {
                        importObject[mod][name] = new WebAssembly.Table({ initial: 0, element: 'anyfunc' });
                    } else if (kind === 'global') {
                        importObject[mod][name] = new WebAssembly.Global({ value: 'i32', mutable: true }, 0);
                    }
                }
                const instance = await WebAssembly.instantiate(module, importObject);

                const exportFn = instance.exports['${exportName}'];
                if (typeof exportFn === 'function') {
                    log('Calling export: ${exportName}(${argsArray})');
                    const result = exportFn(${argsArray});
                    log('Result: ' + result, 'success');
                } else if (exportFn !== undefined) {
                    log('Export "${exportName}" is not a function, value: ' + exportFn, 'info');
                } else {
                    const available = Object.keys(instance.exports).join(', ');
                    log('Export "${exportName}" not found. Available exports: ' + available, 'error');
                }
            } catch (e) {
                log('Error: ' + e.message, 'error');
                console.error(e);
            }
        })();
    </script>
</body>
</html>`;
}

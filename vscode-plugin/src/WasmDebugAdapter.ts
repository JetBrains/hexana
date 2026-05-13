import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { execFileSync } from "child_process";
import { extensionLib, getRuntimeCommand } from "./WasmRunConfigurations";
import type * as ExtensionLib from "./extension-lib";

/**
 * Debug configuration type identifier registered in package.json.
 */
const DEBUG_TYPE = "wasm";

/**
 * Resolves the `lldb-dap` executable path from settings, environment,
 * or well-known locations.
 *
 * Resolution order:
 * 1. `hexana.lldbDapPath` VS Code setting
 * 2. `lldb-dap` on the system PATH
 * 3. Common Homebrew / LLVM installation directories on macOS/Linux
 */
function resolveLldbDapPath(): string | null {
    const config = vscode.workspace.getConfiguration("hexana");
    const custom = config.get<string>("lldbDapPath", "").trim();
    if (custom) {
        return custom;
    }

    const isWindows = os.platform() === "win32";
    const exeName = isWindows ? "lldb-dap.exe" : "lldb-dap";

    // Check PATH first
    try {
        const whichCmd = isWindows ? "where.exe" : "which";
        const result = execFileSync(whichCmd, [exeName], { stdio: "pipe" }).toString().trim();
        if (result) {
            return result.split("\n")[0].trim();
        }
    } catch {
        // Not on PATH
    }

    // Check well-known locations
    const candidates: string[] = [];
    if (os.platform() === "darwin") {
        // Homebrew LLVM installations
        candidates.push(
            "/opt/homebrew/opt/llvm/bin/lldb-dap",
            "/usr/local/opt/llvm/bin/lldb-dap",
        );
        // Xcode command-line tools (may not have lldb-dap)
        candidates.push("/usr/bin/lldb-dap");
    } else if (!isWindows) {
        // Linux common locations
        for (const ver of ["19", "18", "17", "16", "15", ""]) {
            const suffix = ver ? `-${ver}` : "";
            candidates.push(`/usr/bin/lldb-dap${suffix}`);
            candidates.push(`/usr/lib/llvm${suffix}/bin/lldb-dap`);
        }
    }

    for (const candidate of candidates) {
        if (fs.existsSync(candidate)) {
            return candidate;
        }
    }

    return null;
}

/**
 * Resolves the `lldb` executable path for WAMR remote debugging.
 */
function resolveLldbPath(): string | null {
    const config = vscode.workspace.getConfiguration("hexana");
    const custom = config.get<string>("lldbPath", "").trim();
    if (custom) {
        return custom;
    }

    const isWindows = os.platform() === "win32";
    const exeName = isWindows ? "lldb.exe" : "lldb";

    try {
        const whichCmd = isWindows ? "where.exe" : "which";
        execFileSync(whichCmd, [exeName], { stdio: "pipe" });
        return exeName;
    } catch {
        // Not on PATH
    }

    if (os.platform() === "darwin") {
        const candidates = [
            "/opt/homebrew/opt/llvm/bin/lldb",
            "/usr/local/opt/llvm/bin/lldb",
            "/usr/bin/lldb",
        ];
        for (const c of candidates) {
            if (fs.existsSync(c)) {
                return c;
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
 * Node.js implementation of the JsStubFileWriter interface for
 * writing temporary stub files during debug session setup.
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
 * A VS Code `DebugConfigurationProvider` that fills in default values
 * for `wasm` debug launch configurations.
 */
class WasmDebugConfigurationProvider implements vscode.DebugConfigurationProvider {
    resolveDebugConfiguration(
        _folder: vscode.WorkspaceFolder | undefined,
        config: vscode.DebugConfiguration,
        _token?: vscode.CancellationToken,
    ): vscode.ProviderResult<vscode.DebugConfiguration> {
        // If launched without a launch.json, provide defaults
        if (!config.type && !config.request && !config.name) {
            const editor = vscode.window.activeTextEditor;
            if (editor && editor.document.fileName.endsWith(".wasm")) {
                config.type = DEBUG_TYPE;
                config.name = "Debug Wasm";
                config.request = "launch";
                config.wasmFile = "${file}";
                config.runtime = "wasmtime";
            }
        }

        if (!config.wasmFile) {
            return vscode.window.showInformationMessage(
                "Cannot debug: no .wasm file specified in the debug configuration.",
            ).then(() => undefined);
        }

        // Default runtime
        if (!config.runtime) {
            config.runtime = "wasmtime";
        }

        return config;
    }
}

/**
 * A VS Code `DebugAdapterDescriptorFactory` that launches `lldb-dap`
 * as the debug adapter for Wasmtime debugging.
 *
 * For Wasmtime:
 *   `lldb-dap` is launched directly. The launch configuration passes
 *   wasmtime as the program with debug-info flags, and the .wasm file
 *   as an argument.
 *
 * For WAMR:
 *   `iwasm -g=host:port module.wasm` is started as a separate process,
 *   then `lldb-dap` is launched and configured to connect to the
 *   WAMR GDB remote stub.
 */
class WasmDebugAdapterFactory implements vscode.DebugAdapterDescriptorFactory {
    createDebugAdapterDescriptor(
        session: vscode.DebugSession,
        _executable: vscode.DebugAdapterExecutable | undefined,
    ): vscode.ProviderResult<vscode.DebugAdapterDescriptor> {
        const config = session.configuration;
        const runtime: string = config.runtime || "wasmtime";

        if (runtime !== "wasmtime" && runtime !== "wamr") {
            vscode.window.showErrorMessage(
                `Debugging is only supported for Wasmtime and WAMR runtimes, not '${runtime}'.`,
            );
            return undefined;
        }

        const lldbDapPath = resolveLldbDapPath();
        if (!lldbDapPath) {
            vscode.window.showErrorMessage(
                "Cannot start debug session: `lldb-dap` was not found. " +
                "Install LLDB 15+ (which includes lldb-dap), or set " +
                "'hexana.lldbDapPath' in settings to the lldb-dap executable path. " +
                "On macOS: `brew install llvm`; on Linux: `apt install lldb`.",
            );
            return undefined;
        }

        const args: string[] = [];

        // On macOS, enable JIT loader for Wasmtime so LLDB can see
        // JIT-compiled symbols.  The lldb-dap binary accepts
        // `--pre-init-command` (alias `-c`) — NOT `--init-command`.
        if (os.platform() === "darwin" && runtime === "wasmtime") {
            args.push(
                "--pre-init-command",
                "settings set plugin.jit-loader.gdb.enable on",
            );
        }

        return new vscode.DebugAdapterExecutable(lldbDapPath, args);
    }
}

/**
 * Builds the lldb-dap launch request body for a Wasmtime debug session.
 * This is called by the debug adapter tracker to inject the correct
 * program and arguments into the DAP launch request.
 */
function buildWasmtimeLaunchConfig(config: vscode.DebugConfiguration): Record<string, unknown> {
    if (!extensionLib) {
        throw new Error("Extension library is not available");
    }

    const wasmFilePath = config.wasmFile as string;
    const wasmBytes = readWasmBytes(wasmFilePath);
    const programArgs = config.programArgs || "";
    const runtimeOptions = config.runtimeOptions || "";
    const exportName = (config.exportName || "") as string;
    const invokeArgs = (config.invokeArgs || "") as string;

    const isWindows = os.platform() === "win32";
    const wasmtimeCmd = getRuntimeCommand("wasmtime", isWindows);
    if (!wasmtimeCmd) {
        throw new Error("Cannot resolve wasmtime executable");
    }

    const debugCmdLine = extensionLib.buildWasmtimeDebugCommandLine(
        wasmFilePath,
        wasmBytes,
        programArgs,
        runtimeOptions,
        null,
    );

    const debugArgs = [...debugCmdLine.arguments];

    // When debugging a specific export (e.g. from the hex viewer's
    // exports tab), inject `--invoke <name>` so wasmtime calls that
    // function instead of the default `_start` entry point.  The
    // flag must appear before the .wasm path in the argument list.
    if (exportName) {
        // Find the position of the .wasm file path in the args and
        // insert --invoke before it.
        const wasmIdx = debugArgs.findIndex(
            (a) => a === wasmFilePath || a.endsWith(".wasm"),
        );
        if (wasmIdx >= 0) {
            debugArgs.splice(wasmIdx, 0, "--invoke", exportName);
        }

        // Append invoke arguments (the function parameters) after
        // the program arguments so they are passed to wasmtime.
        const trimmedInvokeArgs = invokeArgs.trim();
        if (trimmedInvokeArgs) {
            debugArgs.push(
                ...trimmedInvokeArgs.split(/\s+/),
            );
        }
    }

    return {
        program: wasmtimeCmd,
        args: debugArgs,
        cwd: debugCmdLine.workingDirectory || path.dirname(wasmFilePath),
    };
}

/**
 * A `DebugAdapterTracker` that intercepts the DAP `launch` request
 * to inject the correct program, arguments, and settings for the
 * Wasm debug session.
 */
class WasmDebugAdapterTracker implements vscode.DebugAdapterTracker {
    constructor(private readonly session: vscode.DebugSession) {}

    onWillReceiveMessage(message: {
        type: string;
        command?: string;
        arguments?: Record<string, unknown>;
    }): void {
        if (message.type !== "request" || message.command !== "launch") {
            return;
        }

        const config = this.session.configuration;
        const runtime: string = config.runtime || "wasmtime";

        try {
            if (runtime === "wasmtime") {
                const launchConfig = buildWasmtimeLaunchConfig(config);
                const args = message.arguments || {};
                args.program = launchConfig.program;
                args.args = launchConfig.args;
                args.cwd = launchConfig.cwd;

                // On macOS, enable the JIT loader inside the DAP
                // launch request as well so LLDB can discover
                // Wasmtime's JIT-compiled Wasm symbols and resolve
                // breakpoints set in Wasm source files.
                if (os.platform() === "darwin") {
                    const initCmds =
                        (args.initCommands as string[] | undefined) || [];
                    initCmds.push(
                        "settings set plugin.jit-loader.gdb.enable on",
                    );
                    args.initCommands = initCmds;
                }

                message.arguments = args;
            }
            // WAMR uses remote attach — handled separately
        } catch (e) {
            const errMsg = e instanceof Error ? e.message : String(e);
            vscode.window.showErrorMessage(
                `Failed to configure debug session: ${errMsg}`,
            );
        }
    }
}

class WasmDebugAdapterTrackerFactory implements vscode.DebugAdapterTrackerFactory {
    createDebugAdapterTracker(
        session: vscode.DebugSession,
    ): vscode.ProviderResult<vscode.DebugAdapterTracker> {
        return new WasmDebugAdapterTracker(session);
    }
}

/**
 * Registers all debug-related providers with VS Code.
 *
 * @param context the extension context for registering disposables
 */
export function registerWasmDebugAdapter(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.debug.registerDebugConfigurationProvider(
            DEBUG_TYPE,
            new WasmDebugConfigurationProvider(),
        ),
    );

    context.subscriptions.push(
        vscode.debug.registerDebugAdapterDescriptorFactory(
            DEBUG_TYPE,
            new WasmDebugAdapterFactory(),
        ),
    );

    context.subscriptions.push(
        vscode.debug.registerDebugAdapterTrackerFactory(
            DEBUG_TYPE,
            new WasmDebugAdapterTrackerFactory(),
        ),
    );
}

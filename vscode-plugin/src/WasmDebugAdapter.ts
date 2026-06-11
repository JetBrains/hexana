import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import type * as ExtensionLib from "./extension-lib";
import { CdpDebugAdapter } from "./CdpDebugAdapter";
import {
    LldbDapProxy,
    resolveLldbDapPath,
    launchIwasmDebugServer,
    waitForIwasmReady,
    WAMR_DEBUG_SERVER_READY_TIMEOUT_MS,
    buildWasmtimeLaunchConfig,
    writeWindowsJitLoaderScript,
} from "./LldbDapDebugAdapter";

/**
 * Debug configuration type identifier registered in package.json.
 */
const DEBUG_TYPE = "wasm";

/**
 * Reads the raw bytes of a wasm file, returning `null` on failure.
 * Used by both the CDP adapter (for function offset parsing) and
 * the LLDB helpers (for command-line construction).
 */
export function readWasmBytes(filePath: string): Int8Array | null {
    try {
        const buf = fs.readFileSync(filePath);
        return new Int8Array(buf.buffer, buf.byteOffset, buf.byteLength);
    } catch {
        return null;
    }
}

/**
 * Node.js implementation of the JsStubFileWriter interface for
 * writing temporary stub files (used by WAMR and browser debug
 * flows).
 */
export const nodeStubWriter: ExtensionLib.JsStubFileWriter = {
    createTempDir(prefix: string): string {
        return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
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
 * Output channel for debug adapter diagnostic logging.
 */
let debugOutputChannel: vscode.OutputChannel | undefined;

export function getDebugOutputChannel(): vscode.OutputChannel {
    if (!debugOutputChannel) {
        debugOutputChannel = vscode.window.createOutputChannel("Hexana Wasm Debug");
    }
    return debugOutputChannel;
}

/**
 * A VS Code `DebugAdapterDescriptorFactory` that selects the
 * appropriate debug adapter based on the chosen runtime.
 *
 * For Node.js / Browser:
 *   A `CdpDebugAdapter` is returned as an inline implementation.
 *   It launches Node.js with `--inspect-brk` or Chrome with
 *   `--remote-debugging-port`, connects via CDP WebSocket, and
 *   translates DAP ↔ CDP — matching the IntelliJ plugin's
 *   `CdpDebugProcess` implementation.
 *
 * For Wasmtime:
 *   An `LldbDapProxy` is returned as an inline implementation.
 *   The proxy spawns lldb-dap as a child process and intercepts DAP
 *   messages to inject `__vmctx->set()` before variable inspection,
 *   matching the IntelliJ plugin's on-demand approach.
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

        // CDP-based debugging for Node.js and Browser runtimes
        if (runtime === "nodejs" || runtime === "browser") {
            const adapter = new CdpDebugAdapter(runtime, config);
            return new vscode.DebugAdapterInlineImplementation(adapter);
        }

        if (runtime !== "wasmtime" && runtime !== "wamr") {
            vscode.window.showErrorMessage(
                `Debugging is only supported for Wasmtime, WAMR, Node.js, and Browser runtimes, not '${runtime}'.`,
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

        // Enable JIT loader for Wasmtime so LLDB can see
        // JIT-compiled symbols.  The lldb-dap binary accepts
        // `--pre-init-command` (alias `-c`) — NOT `--init-command`.
        //
        // On macOS/Linux, this is required for LLDB to
        // automatically discover symbols in JIT-compiled code.
        // On Windows, the built-in JIT loader does not work
        // reliably (the IntelliJ plugin uses a manual workaround),
        // but enabling it is still attempted as a best-effort
        // measure for lldb-dap which handles LLDB internally.
        if (runtime === "wasmtime") {
            args.push(
                "--pre-init-command",
                "settings set plugin.jit-loader.gdb.enable on",
            );
        }

        // For Wasmtime, use the inline proxy adapter so we can
        // intercept DAP messages and inject __vmctx->set() on
        // demand before variable inspection.
        if (runtime === "wasmtime") {
            const proxy = new LldbDapProxy(lldbDapPath, args, true);
            proxy.start();
            return new vscode.DebugAdapterInlineImplementation(proxy);
        }

        // For WAMR: launch iwasm with the debug server, wait for
        // it to be ready, then return an LldbDapProxy that will
        // be configured with `launchCommands` by the tracker.
        if (runtime === "wamr") {
            const wamrResult = launchIwasmDebugServer(config);
            if (!wamrResult) {
                return undefined; // error already shown
            }

            // Store connection info on the session config so the
            // tracker can inject `launchCommands` later.
            config._wamrDebugHost = wamrResult.debugHost;
            config._wamrDebugPort = wamrResult.debugPort;

            // Wait for iwasm to print "Debug server listening on"
            // on stdout (matching IntelliJ's approach), or for the
            // process to exit early.  Only after the server is
            // confirmed ready do we return the debug adapter.
            return waitForIwasmReady(
                wamrResult.iwasmProcess,
                WAMR_DEBUG_SERVER_READY_TIMEOUT_MS,
            ).then(() => {
                const proxy = new LldbDapProxy(lldbDapPath, args, false);
                proxy.setIwasmProcess(wamrResult.iwasmProcess);
                proxy.start();
                return new vscode.DebugAdapterInlineImplementation(proxy);
            }, (err) => {
                wamrResult.iwasmProcess.kill();
                const errMsg = err instanceof Error ? err.message : String(err);
                vscode.window.showErrorMessage(
                    `WAMR debug server did not become ready: ${errMsg}`,
                );
                return undefined;
            });
        }

        return new vscode.DebugAdapterExecutable(lldbDapPath, args);
    }
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

                // Disable ASLR control so that wasmtime does not
                // call personality(2), which is blocked by seccomp
                // in many CI / container environments ("personality
                // set failed: Operation not permitted").
                args.disableASLR = false;

                // Enable the JIT loader inside the DAP launch
                // request so LLDB can discover Wasmtime's
                // JIT-compiled Wasm symbols and resolve breakpoints
                // set in Wasm source files.  On Windows the built-in
                // JIT loader is unreliable but enabling it is still
                // a best-effort measure for lldb-dap.
                {
                    const initCmds =
                        (args.initCommands as string[] | undefined) || [];
                    initCmds.push(
                        "settings set plugin.jit-loader.gdb.enable on",
                    );
                    args.initCommands = initCmds;
                }

                // Wasmtime JIT emits trap instructions at
                // breakpoint sites: `brk` (4 bytes) on ARM64,
                // `ud2` (2 bytes) on x86_64.  When LLDB stops on
                // such a trap the PC still points at the trap; a
                // plain `continue` would re-execute the same trap,
                // causing an infinite loop.
                //
                // The stopCommands below run after every stop and
                // conditionally advance the PC past the trap when
                // the current instruction is indeed a trap.  The
                // `?` prefix silences console output on success.
                //
                // This handles both ARM64 (`brk`, 4 bytes) and
                // x86_64 (`ud2`, 2 bytes) — matching the shared
                // LLDB_DAP_TRAP_ADVANCE_STOP_CMD constant from
                // plugins-shared.
                //
                // Note: __vmctx->set() is NOT placed here — running
                // `expression` in stopCommands interferes with
                // breakpoint resolution.  Instead, the LldbDapProxy
                // intercepts scopes responses and injects
                // __vmctx->set() after the frame is selected but
                // before variables are requested (matching
                // IntelliJ's approach).
                {
                    const stopCmds =
                        (args.stopCommands as string[] | undefined) || [];
                    stopCmds.push(
                        "?script import lldb; t = lldb.debugger.GetSelectedTarget(); p = t.GetProcess(); " +
                        "f = p.GetSelectedThread().GetSelectedFrame(); err = lldb.SBError(); pc = f.GetPC(); " +
                        "triple = t.GetTriple(); is_arm = 'arm' in triple or 'aarch64' in triple; " +
                        "inst = p.ReadUnsignedFromMemory(pc, 4, err) if is_arm else p.ReadUnsignedFromMemory(pc, 2, err); " +
                        "is_trap = (err.Success() and ((inst & 0xFFE0001F) == 0xD4200000)) if is_arm else " +
                        "(err.Success() and inst == 0x0B0F); " +
                        "f.SetPC(pc + (4 if is_arm else 2)) if is_trap else None",
                    );
                    args.stopCommands = stopCmds;
                }

                // On Windows, the built-in JIT loader
                // (plugin.jit-loader.gdb.enable) does not work
                // reliably — LLDB never discovers Wasmtime's
                // JIT-compiled symbols, so source breakpoints
                // never resolve and the process runs to completion
                // immediately.
                //
                // Workaround: write a Python helper script to a
                // temp file that defines a breakpoint callback for
                // __jit_debug_register_code.  When the callback
                // fires, it reads the JIT descriptor from inferior
                // memory, dumps the ELF object to disk, loads it
                // into LLDB, scans its line tables, and sets
                // address breakpoints for any pending source
                // breakpoints that match.  The callback returns
                // False to auto-continue so the process only stops
                // at resolved user breakpoints.  This replicates
                // the IntelliJ plugin's windowsJitBreakpointSetup.
                if (os.platform() === "win32") {
                    const jitScript = writeWindowsJitLoaderScript();
                    if (jitScript) {
                        const preRunCmds =
                            (args.preRunCommands as string[] | undefined) || [];
                        const escapedPath = jitScript.replace(/\\/g, "/");
                        // The script's __lldb_init_module sets the
                        // breakpoint on __jit_debug_register_code and
                        // attaches the callback atomically — no
                        // separate breakpoint set / command add needed.
                        preRunCmds.push(
                            `command script import "${escapedPath}"`,
                        );
                        args.preRunCommands = preRunCmds;
                    }
                }

                message.arguments = args;
            } else if (runtime === "wamr") {
                // WAMR uses a GDB remote debug stub model:
                // iwasm is already running (launched by the factory),
                // and we configure lldb-dap to connect via
                // `process connect --plugin wasm connect://host:port`.
                //
                // IMPORTANT: Do NOT set `program` — the WAMR wasm
                // process plugin in LLDB creates its own target from
                // the remote stub.  A pre-existing local target from
                // `target create` (which lldb-dap does when `program`
                // is set) interferes with the plugin.
                const debugHost = config._wamrDebugHost || "127.0.0.1";
                const debugPort = config._wamrDebugPort || 1234;

                const args = message.arguments || {};
                args.launchCommands = [
                    `process connect --plugin wasm connect://${debugHost}:${debugPort}`,
                ];
                // Force stopOnEntry so that configurationDone sends
                // a stopped event instead of calling
                // process.Continue().  The LldbDapProxy will
                // intercept this initial stop and check if it
                // matches a user breakpoint (matching IntelliJ's
                // checkInitialStopMatchesBreakpoint); if not, it
                // sends `continue` itself.
                args.stopOnEntry = true;
                message.arguments = args;
            }
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

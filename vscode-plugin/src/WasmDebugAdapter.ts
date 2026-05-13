import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { execFileSync, spawn, ChildProcess } from "child_process";
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
 * Output channel for debug adapter diagnostic logging.
 */
let debugOutputChannel: vscode.OutputChannel | undefined;

function getDebugOutputChannel(): vscode.OutputChannel {
    if (!debugOutputChannel) {
        debugOutputChannel = vscode.window.createOutputChannel("Hexana Wasm Debug");
    }
    return debugOutputChannel;
}

/**
 * DAP message interface used for JSON-level manipulation.
 * The actual wire format adds `Content-Length` headers.
 */
interface DapMessage {
    seq: number;
    type: string;
    command?: string;
    request_seq?: number;
    event?: string;
    success?: boolean;
    body?: Record<string, unknown>;
    arguments?: Record<string, unknown>;
    [key: string]: unknown;
}

/**
 * An inline debug adapter that proxies DAP messages between VS Code
 * and an lldb-dap child process.  It intercepts `scopes` responses
 * from lldb-dap and injects an `expression -- __vmctx->set()` call
 * before forwarding the response to VS Code.
 *
 * The key insight is that lldb-dap's `ScopesRequestHandler` selects
 * the correct frame (`SetSelectedFrame`) when processing a scopes
 * request.  By waiting for the scopes RESPONSE (meaning the frame
 * is already selected), the injected LLDB `expression` command runs
 * on the correct Wasm frame — matching IntelliJ's
 * `WasmStackFrame.computeChildren()` which does `frame select` then
 * `expression -- __vmctx->set()` then `frame variable`.
 *
 * The VM context is initialized on-demand before variable inspection,
 * not on every stop (which would interfere with breakpoint
 * resolution).
 */
class LldbDapProxy implements vscode.DebugAdapter {
    private readonly emitter = new vscode.EventEmitter<vscode.DebugProtocolMessage>();
    readonly onDidSendMessage: vscode.Event<vscode.DebugProtocolMessage> = this.emitter.event;

    private process: ChildProcess | undefined;
    private rawBuffer = Buffer.alloc(0);
    private nextSeq = 100_000;
    /**
     * Maps the seq of an injected `__vmctx->set()` evaluate
     * request to the original `scopes` response that was
     * deferred until the evaluate completes.
     */
    private readonly pendingVmCtx = new Map<number, DapMessage>();
    private readonly log = getDebugOutputChannel();
    private isWasmtime: boolean;

    constructor(
        private readonly lldbDapPath: string,
        private readonly cliArgs: string[],
        isWasmtime: boolean,
    ) {
        this.isWasmtime = isWasmtime;
    }

    /**
     * Called by VS Code to send a DAP message (request) to the
     * debug adapter.
     */
    handleMessage(message: vscode.DebugProtocolMessage): void {
        const msg = message as unknown as DapMessage;
        this.log.appendLine(`[DAP →] ${msg.type}: ${msg.command || msg.event || ""} ${JSON.stringify(msg.arguments || msg.body || "").substring(0, 200)}`);

        this.sendToLldbDap(msg);
    }

    /**
     * Starts the lldb-dap child process.
     */
    start(): void {
        this.log.appendLine(`[Hexana] Starting lldb-dap: ${this.lldbDapPath} ${this.cliArgs.join(" ")}`);
        this.process = spawn(this.lldbDapPath, this.cliArgs, {
            stdio: ["pipe", "pipe", "pipe"],
        });

        this.process.stdout?.on("data", (chunk: Buffer) => {
            this.onStdoutData(chunk);
        });

        this.process.stderr?.on("data", (chunk: Buffer) => {
            this.log.appendLine(`[lldb-dap stderr] ${chunk.toString()}`);
        });

        this.process.on("error", (err) => {
            this.log.appendLine(`[DAP ERROR] ${err.message}`);
        });

        this.process.on("exit", (code, signal) => {
            this.log.appendLine(`[DAP EXIT] code=${code}, signal=${signal}`);
        });
    }

    dispose(): void {
        if (this.process) {
            this.process.kill();
            this.process = undefined;
        }
        this.emitter.dispose();
    }

    /**
     * Serializes a DAP message and writes it to lldb-dap's stdin.
     */
    private sendToLldbDap(msg: DapMessage): void {
        const json = JSON.stringify(msg);
        const header = `Content-Length: ${Buffer.byteLength(json, "utf-8")}\r\n\r\n`;
        this.process?.stdin?.write(header + json, "utf-8");
    }

    /**
     * Accumulates stdout data from lldb-dap, parses complete DAP
     * messages, and forwards them to VS Code (or handles injected
     * responses internally).
     */
    private onStdoutData(chunk: Buffer): void {
        this.rawBuffer = Buffer.concat([this.rawBuffer, chunk]);

        // eslint-disable-next-line no-constant-condition
        while (true) {
            const headerEnd = this.rawBuffer.indexOf("\r\n\r\n");
            if (headerEnd < 0) break;

            const headerStr = this.rawBuffer.subarray(0, headerEnd).toString("utf-8");
            const match = headerStr.match(/Content-Length:\s*(\d+)/i);
            if (!match) {
                // Malformed header — skip past it
                this.rawBuffer = this.rawBuffer.subarray(headerEnd + 4);
                continue;
            }

            const contentLength = parseInt(match[1], 10);
            const totalLength = headerEnd + 4 + contentLength;
            if (this.rawBuffer.length < totalLength) break;

            const body = this.rawBuffer.subarray(headerEnd + 4, totalLength).toString("utf-8");
            this.rawBuffer = this.rawBuffer.subarray(totalLength);

            let parsed: DapMessage;
            try {
                parsed = JSON.parse(body) as DapMessage;
            } catch {
                continue;
            }

            this.handleLldbDapMessage(parsed);
        }
    }

    /**
     * Processes a parsed DAP message from lldb-dap.
     *
     * For Wasmtime sessions, intercepts `scopes` responses to
     * inject `__vmctx->set()` before VS Code sees the response.
     *
     * The ScopesRequestHandler in lldb-dap selects the correct
     * frame (`SetSelectedFrame`) when processing the scopes
     * request.  By waiting until the scopes RESPONSE comes back,
     * we know the frame is already selected.  We then inject
     * `expression -- __vmctx->set()` (via the backtick-prefixed
     * "repl" context, which forces the LLDB CLI command path
     * matching IntelliJ's `LldbCommunicator`) to initialize
     * Wasmtime's VM context.  The deferred scopes response is
     * forwarded to VS Code only after the evaluate completes,
     * ensuring the VM context is ready before VS Code sends any
     * `variables` requests.
     *
     * See: https://docs.wasmtime.dev/examples-debugging-native-debugger.html
     */
    private handleLldbDapMessage(msg: DapMessage): void {
        // Check if this is a response to our injected
        // __vmctx->set() evaluate request.
        if (
            msg.type === "response" &&
            msg.request_seq !== undefined &&
            this.pendingVmCtx.has(msg.request_seq)
        ) {
            const deferredScopesResponse = this.pendingVmCtx.get(msg.request_seq)!;
            this.pendingVmCtx.delete(msg.request_seq);

            this.log.appendLine(
                `[Hexana] __vmctx->set() response (seq=${msg.request_seq}, success=${msg.success}, ` +
                `result=${JSON.stringify((msg.body as Record<string, unknown>)?.result ?? "").substring(0, 200)}); ` +
                `forwarding deferred scopes response (seq=${deferredScopesResponse.request_seq})`,
            );
            // Now forward the original scopes response to VS Code.
            this.emitter.fire(deferredScopesResponse as unknown as vscode.DebugProtocolMessage);
            return;
        }

        // For Wasmtime sessions, intercept successful scopes
        // responses.  At this point lldb-dap has already selected
        // the frame, so the LLDB CLI `expression` command will
        // execute on the correct Wasm frame.
        if (
            this.isWasmtime &&
            msg.type === "response" &&
            msg.command === "scopes" &&
            msg.success
        ) {
            const vmCtxSeq = this.nextSeq++;
            const evalRequest: DapMessage = {
                seq: vmCtxSeq,
                type: "request",
                command: "evaluate",
                arguments: {
                    expression: "`expression -- __vmctx->set()",
                    context: "repl",
                },
            };
            this.pendingVmCtx.set(vmCtxSeq, msg);
            this.log.appendLine(
                `[Hexana] Scopes response received; injecting __vmctx->set() ` +
                `(seq=${vmCtxSeq}) before forwarding scopes response`,
            );
            this.sendToLldbDap(evalRequest);
            return;
        }

        this.log.appendLine(`[DAP ←] ${msg.type}: ${msg.command || msg.event || ""} success=${msg.success ?? ""} ${JSON.stringify(msg.body || "").substring(0, 200)}`);
        this.emitter.fire(msg as unknown as vscode.DebugProtocolMessage);
    }
}

/**
 * A VS Code `DebugAdapterDescriptorFactory` that launches `lldb-dap`
 * as the debug adapter for Wasmtime debugging.
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

        // For Wasmtime, use the inline proxy adapter so we can
        // intercept DAP messages and inject __vmctx->set() on
        // demand before variable inspection.
        if (runtime === "wasmtime") {
            const proxy = new LldbDapProxy(lldbDapPath, args, true);
            proxy.start();
            return new vscode.DebugAdapterInlineImplementation(proxy);
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

                // On macOS/ARM64, Wasmtime JIT emits 4-byte `brk`
                // (trap) instructions at breakpoint sites.  When
                // LLDB stops on such a trap the PC still points at
                // the `brk`; a plain `continue` would re-execute
                // the same trap, causing an infinite
                // EXC_BREAKPOINT loop.
                //
                // The stopCommands below run after every stop and
                // advance the PC past the 4-byte `brk` when the
                // current instruction is indeed a trap.  The `?`
                // prefix silences console output on success.
                //
                // Note: __vmctx->set() is NOT placed here — running
                // `expression` in stopCommands interferes with
                // breakpoint resolution.  Instead, the LldbDapProxy
                // intercepts scopes responses and injects
                // __vmctx->set() after the frame is selected but
                // before variables are requested (matching
                // IntelliJ's approach).
                if (
                    os.platform() === "darwin" &&
                    os.arch() === "arm64"
                ) {
                    const stopCmds =
                        (args.stopCommands as string[] | undefined) || [];
                    stopCmds.push(
                        "?script import lldb; t = lldb.debugger.GetSelectedTarget().GetProcess().GetSelectedThread(); " +
                        "f = t.GetSelectedFrame(); " +
                        "err = lldb.SBError(); " +
                        "inst = f.GetThread().GetProcess().ReadUnsignedFromMemory(f.GetPC(), 4, err); " +
                        "f.SetPC(f.GetPC() + 4) if err.Success() and (inst & 0xFFE0001F) == 0xD4200000 else None",
                    );
                    args.stopCommands = stopCmds;
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

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
    /**
     * Maps the `request_seq` of a `setBreakpoints` request to
     * the original full source info that was rewritten to
     * basename-only before forwarding to lldb-dap.
     * Used to restore the original paths in the response so
     * VS Code still associates breakpoints with the correct
     * source files.
     */
    private readonly pendingBpSource = new Map<number, { path: string; name: string }>();
    /**
     * Persistent mapping from source-file basename to the full
     * IDE path, built from `setBreakpoints` requests.  Used to
     * resolve DWARF-relative paths in `stackTrace` responses and
     * `breakpoint` events back to the actual local files so VS
     * Code can open them.  Matches IntelliJ's
     * `resolveFileByDwarfPath()` suffix-matching approach.
     *
     * When multiple full paths share the same basename, the map
     * stores all of them; `resolveSourcePath` tries suffix
     * matching to disambiguate.
     */
    private readonly sourcePathMap = new Map<string, Set<string>>();
    /**
     * Tracks registered breakpoint locations (basename → set of
     * line numbers) from `setBreakpoints` requests.  Used by the
     * WAMR initial-stop handler to check whether the first `trace`
     * stop matches a user breakpoint — matching IntelliJ's
     * `checkInitialStopMatchesBreakpoint()`.
     */
    private readonly registeredBreakpoints = new Map<string, Set<number>>();
    /**
     * Whether we are still waiting for the first WAMR `stopped`
     * event (the initial `trace` stop from `process connect`).
     * Set to `true` when `configurationDone` is forwarded for a
     * WAMR session with `stopOnEntry`, and cleared after the
     * initial stop is handled.
     */
    private wamrInitialStopPending = false;
    private readonly log = getDebugOutputChannel();
    private isWasmtime: boolean;

    /**
     * Optional iwasm child process for WAMR debugging.
     * The proxy owns this process and kills it on dispose.
     */
    private iwasmProcess: ChildProcess | undefined;

    constructor(
        private readonly lldbDapPath: string,
        private readonly cliArgs: string[],
        isWasmtime: boolean,
    ) {
        this.isWasmtime = isWasmtime;
    }

    /**
     * Registers an iwasm child process to be managed by this proxy.
     * The process will be killed when the proxy is disposed.
     */
    setIwasmProcess(proc: ChildProcess): void {
        this.iwasmProcess = proc;
    }

    /**
     * Called by VS Code to send a DAP message (request) to the
     * debug adapter.
     */
    handleMessage(message: vscode.DebugProtocolMessage): void {
        const msg = message as unknown as DapMessage;
        this.log.appendLine(`[DAP →] ${msg.type}: ${msg.command || msg.event || ""} ${JSON.stringify(msg.arguments || msg.body || "").substring(0, 200)}`);

        // Rewrite setBreakpoints source paths to use basename
        // only.  DWARF debug info inside Wasm binaries records
        // relative source paths (e.g. "src/lib.rs", "main.go").
        // LLDB's `breakpoint set --file` matches against
        // compilation-unit filenames, so a full absolute IDE path
        // would fail to resolve.  The IntelliJ plugin uses
        // `position.file.name` (basename) for exactly this
        // reason (see registerBreakpointInLldb /
        // replayBreakpointsToLldb).
        //
        // We save the original full path per-request (for
        // response patching) and in a persistent basename→path
        // map (for resolving DWARF paths in stackTrace
        // responses).
        if (
            msg.type === "request" &&
            msg.command === "setBreakpoints" &&
            msg.arguments
        ) {
            const source = msg.arguments.source as Record<string, unknown> | undefined;
            const sourcePath = source?.path as string | undefined;
            if (source && sourcePath) {
                const baseName = path.basename(sourcePath);
                this.pendingBpSource.set(
                    msg.seq as number,
                    { path: sourcePath, name: (source.name as string) || baseName },
                );
                // Record in persistent map for stackTrace
                // response path resolution.
                let paths = this.sourcePathMap.get(baseName);
                if (!paths) {
                    paths = new Set();
                    this.sourcePathMap.set(baseName, paths);
                }
                paths.add(sourcePath);
                // Track breakpoint lines for the initial-stop
                // matching used in WAMR sessions.
                const bpLines = msg.arguments.breakpoints as Array<{ line: number }> | undefined;
                if (bpLines) {
                    let lineSet = this.registeredBreakpoints.get(baseName);
                    if (!lineSet) {
                        lineSet = new Set();
                        this.registeredBreakpoints.set(baseName, lineSet);
                    }
                    for (const bp of bpLines) {
                        lineSet.add(bp.line);
                    }
                }
                source.path = baseName;
                source.name = baseName;
                this.log.appendLine(
                    `[Hexana] Rewrote setBreakpoints source path ` +
                    `from '${sourcePath}' to basename '${baseName}'`,
                );
            }
        }

        // For WAMR sessions, mark that we expect the initial
        // trace stop after configurationDone so the proxy can
        // check it against registered breakpoints.
        if (
            !this.isWasmtime &&
            msg.type === "request" &&
            msg.command === "configurationDone"
        ) {
            this.wamrInitialStopPending = true;
            this.log.appendLine(
                `[Hexana] WAMR: configurationDone sent, waiting for initial trace stop`,
            );
        }

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
        if (this.iwasmProcess) {
            this.iwasmProcess.kill();
            this.iwasmProcess = undefined;
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
        // Swallow responses to internally-injected requests
        // (e.g. continue after WAMR initial-stop check) so
        // VS Code doesn't see unexpected responses.
        if (
            msg.type === "response" &&
            msg.request_seq !== undefined &&
            this.injectedRequestSeqs.has(msg.request_seq)
        ) {
            this.injectedRequestSeqs.delete(msg.request_seq);
            this.log.appendLine(
                `[Hexana] Swallowed response to injected request ` +
                `(seq=${msg.request_seq}, command=${msg.command})`,
            );
            return;
        }

        // Restore original source paths in setBreakpoints
        // responses so VS Code still shows breakpoints in the
        // correct files.
        if (
            msg.type === "response" &&
            msg.command === "setBreakpoints" &&
            msg.request_seq !== undefined &&
            this.pendingBpSource.has(msg.request_seq)
        ) {
            const original = this.pendingBpSource.get(msg.request_seq)!;
            this.pendingBpSource.delete(msg.request_seq);

            // Patch each breakpoint in the response to use the
            // original full path.
            const body = msg.body as Record<string, unknown> | undefined;
            const breakpoints = body?.breakpoints as Array<Record<string, unknown>> | undefined;
            if (breakpoints) {
                for (const bp of breakpoints) {
                    const bpSource = bp.source as Record<string, unknown> | undefined;
                    if (bpSource) {
                        bpSource.path = original.path;
                        bpSource.name = original.name;
                    }
                }
            }
            this.log.appendLine(
                `[Hexana] Restored original source path ` +
                `'${original.path}' in setBreakpoints response`,
            );
        }

        // Resolve DWARF source paths in stackTrace responses.
        // lldb-dap returns source paths as recorded in DWARF
        // debug info (e.g. "main.go", "src/lib.rs") which VS
        // Code can't open.  We suffix-match them against the
        // registered breakpoint paths to find the full local
        // path — matching IntelliJ's resolveFileByDwarfPath().
        if (
            msg.type === "response" &&
            msg.command === "stackTrace" &&
            msg.success
        ) {
            this.patchSourcePathsInStackTrace(msg);
        }

        // Also patch source paths in breakpoint events
        // (e.g. when a breakpoint becomes verified after JIT
        // compilation resolves it).
        if (
            msg.type === "event" &&
            msg.event === "breakpoint"
        ) {
            this.patchSourcePathInBreakpointEvent(msg);
        }

        // WAMR initial-stop handling: after configurationDone,
        // lldb-dap sends a `stopped` event for the process that
        // was already stopped at a `trace` stop.  We intercept
        // this first stop, query the frame location via
        // stackTrace, and check if it matches a user breakpoint.
        // If it does, we present the stop as a breakpoint hit;
        // otherwise we send `continue` to resume the process.
        // This matches IntelliJ's checkInitialStopMatchesBreakpoint().
        if (
            !this.isWasmtime &&
            this.wamrInitialStopPending &&
            msg.type === "event" &&
            msg.event === "stopped"
        ) {
            this.wamrInitialStopPending = false;
            this.handleWamrInitialStop(msg);
            return;
        }

        // Handle the stackTrace response for our WAMR
        // initial-stop breakpoint check.
        if (
            this.pendingWamrInitialStopCheck &&
            msg.type === "response" &&
            msg.command === "stackTrace" &&
            msg.request_seq === this.pendingWamrInitialStopCheck.seq
        ) {
            const { stoppedEvent } = this.pendingWamrInitialStopCheck;
            this.pendingWamrInitialStopCheck = undefined;

            let matched = false;
            if (msg.success) {
                const stBody = msg.body as Record<string, unknown> | undefined;
                const frames = stBody?.stackFrames as Array<Record<string, unknown>> | undefined;
                const topFrame = frames?.[0];
                if (topFrame) {
                    const source = topFrame.source as Record<string, unknown> | undefined;
                    const sourcePath = (source?.path as string) || (source?.name as string) || "";
                    const line = topFrame.line as number | undefined;
                    const baseName = sourcePath ? path.basename(sourcePath) : "";

                    this.log.appendLine(
                        `[Hexana] WAMR initial stop frame: ${baseName}:${line}`,
                    );

                    if (baseName && line !== undefined) {
                        matched = this.matchesBreakpoint(baseName, line);
                    }
                }
            }

            if (matched) {
                this.log.appendLine(
                    `[Hexana] WAMR initial stop matches breakpoint — presenting stop to VS Code`,
                );
                // Change stop reason from "entry" to "breakpoint"
                // so VS Code highlights it properly.
                const evtBody = stoppedEvent.body as Record<string, unknown> | undefined;
                if (evtBody) {
                    evtBody.reason = "breakpoint";
                }
                this.log.appendLine(`[DAP ←] ${stoppedEvent.type}: ${stoppedEvent.event || ""} ${JSON.stringify(stoppedEvent.body || "").substring(0, 200)}`);
                this.emitter.fire(stoppedEvent as unknown as vscode.DebugProtocolMessage);
            } else {
                this.log.appendLine(
                    `[Hexana] WAMR initial stop does not match any breakpoint — sending continue`,
                );
                const contSeq = this.nextSeq++;
                const continueReq: DapMessage = {
                    seq: contSeq,
                    type: "request",
                    command: "continue",
                    arguments: {
                        threadId: (stoppedEvent.body as Record<string, unknown>)?.threadId,
                    },
                };
                this.injectedRequestSeqs.add(contSeq);
                this.sendToLldbDap(continueReq);
            }
            // Don't forward the stackTrace response to VS Code —
            // it was our internal request.
            return;
        }

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

    /**
     * Handles WAMR's initial `trace` stop after `configurationDone`.
     *
     * Sends a `stackTrace` request to lldb-dap to determine the
     * current frame's source file and line, then checks whether
     * this location matches any registered breakpoint.  If it
     * does, the stopped event is forwarded to VS Code (presenting
     * the hit); otherwise a `continue` request is sent to resume
     * the process.
     *
     * This mirrors IntelliJ's `checkInitialStopMatchesBreakpoint()`
     * in `WamrDebugProcess.kt`.
     */
    private handleWamrInitialStop(stoppedEvent: DapMessage): void {
        const body = stoppedEvent.body as Record<string, unknown> | undefined;
        const threadId = body?.threadId as number | undefined;
        if (threadId === undefined) {
            // Can't query without a threadId — just forward.
            this.log.appendLine(
                `[Hexana] WAMR initial stop: no threadId, forwarding stopped event`,
            );
            this.emitter.fire(stoppedEvent as unknown as vscode.DebugProtocolMessage);
            return;
        }

        // Send stackTrace request to get frame 0's source location.
        const stSeq = this.nextSeq++;
        const stackTraceReq: DapMessage = {
            seq: stSeq,
            type: "request",
            command: "stackTrace",
            arguments: { threadId, startFrame: 0, levels: 1 },
        };

        // Store a handler for the response keyed by request seq.
        this.pendingWamrInitialStopCheck = {
            seq: stSeq,
            stoppedEvent,
        };

        this.log.appendLine(
            `[Hexana] WAMR initial stop: sending stackTrace (seq=${stSeq}) to check frame location`,
        );
        this.sendToLldbDap(stackTraceReq);
    }

    /**
     * Pending WAMR initial-stop check state: the seq of the
     * injected stackTrace request and the deferred stopped event.
     */
    private pendingWamrInitialStopCheck: {
        seq: number;
        stoppedEvent: DapMessage;
    } | undefined;
    /**
     * Seq numbers of internally-injected requests (continue,
     * stackTrace, etc.) whose responses should be swallowed
     * rather than forwarded to VS Code.
     */
    private readonly injectedRequestSeqs = new Set<number>();

    /**
     * Checks if a source file basename + line matches any
     * registered breakpoint.
     */
    private matchesBreakpoint(sourceBasename: string, line: number): boolean {
        const lines = this.registeredBreakpoints.get(sourceBasename);
        return lines !== undefined && lines.has(line);
    }

    /**
     * Resolves a DWARF source path (which may be relative, e.g.
     * "main.go" or "src/lib.rs") to a full local filesystem path
     * using the registered breakpoint paths.
     *
     * Matching strategy (mirrors IntelliJ's
     * `resolveFileByDwarfPath`):
     *
     * 1. Extract the basename from the DWARF path.
     * 2. Look up all registered full paths that share that
     *    basename.
     * 3. If the DWARF path contains directory components
     *    (e.g. "src/lib.rs"), narrow candidates to those whose
     *    full path ends with the DWARF path preceded by a
     *    separator.
     * 4. If exactly one candidate remains, return it; otherwise
     *    return `undefined` (ambiguous or no match).
     */
    private resolveSourcePath(dwarfPath: string): string | undefined {
        if (!dwarfPath) return undefined;
        const normalised = dwarfPath.replace(/\\/g, "/");
        const baseName = path.basename(normalised);
        const candidates = this.sourcePathMap.get(baseName);
        if (!candidates || candidates.size === 0) return undefined;

        // If the DWARF path has directory components, use suffix
        // matching to narrow down candidates.
        if (normalised.includes("/")) {
            const suffix = "/" + normalised;
            const matched: string[] = [];
            for (const fullPath of candidates) {
                if (fullPath.endsWith(suffix) || fullPath === normalised) {
                    matched.push(fullPath);
                }
            }
            if (matched.length === 1) return matched[0];
            // Ambiguous or no suffix match — fall through.
            if (matched.length > 1) {
                this.log.appendLine(
                    `[Hexana] Ambiguous DWARF path '${normalised}' matches ` +
                    `${matched.length} registered files: ${matched.join(", ")}`,
                );
                return undefined;
            }
        }

        // Basename-only: return the single candidate if
        // unambiguous.
        if (candidates.size === 1) {
            return candidates.values().next().value;
        }
        this.log.appendLine(
            `[Hexana] Ambiguous basename '${baseName}' matches ` +
            `${candidates.size} registered files`,
        );
        return undefined;
    }

    /**
     * Patches source paths in all stack frames of a `stackTrace`
     * response, resolving DWARF-relative paths to full local
     * paths so VS Code can open the correct source files.
     */
    private patchSourcePathsInStackTrace(msg: DapMessage): void {
        const body = msg.body as Record<string, unknown> | undefined;
        const frames = body?.stackFrames as Array<Record<string, unknown>> | undefined;
        if (!frames) return;

        for (const frame of frames) {
            const source = frame.source as Record<string, unknown> | undefined;
            if (!source) continue;

            const sourcePath = (source.path as string) || "";
            if (!sourcePath) continue;
            // Skip absolute paths that look like real local files
            // (non-Wasm frames like system libraries).  lldb-dap
            // uses synthetic paths like "/usr/lib/dyld`_dyld_start"
            // for frames without source — those contain a backtick
            // and should be left as-is (they won't resolve anyway).
            if (sourcePath.startsWith("/") && !sourcePath.includes("`")) {
                continue;
            }

            const resolved = this.resolveSourcePath(sourcePath);
            if (resolved) {
                this.log.appendLine(
                    `[Hexana] Resolved stackTrace source path ` +
                    `'${sourcePath}' → '${resolved}'`,
                );
                source.path = resolved;
                source.name = path.basename(resolved);
                // Remove sourceReference so VS Code opens the
                // local file instead of requesting source content
                // from the debug adapter (which would fail with
                // "Unknown source reference").
                if (source.sourceReference !== undefined) {
                    source.sourceReference = 0;
                }
            }
        }
    }

    /**
     * Patches the source path in a `breakpoint` event body,
     * resolving DWARF-relative paths to full local paths.
     */
    private patchSourcePathInBreakpointEvent(msg: DapMessage): void {
        const body = msg.body as Record<string, unknown> | undefined;
        const bp = body?.breakpoint as Record<string, unknown> | undefined;
        if (!bp) return;

        const source = bp.source as Record<string, unknown> | undefined;
        if (!source) return;

        const sourcePath = (source.path as string) || "";
        const resolved = this.resolveSourcePath(sourcePath);
        if (resolved) {
            this.log.appendLine(
                `[Hexana] Resolved breakpoint event source path ` +
                `'${sourcePath}' → '${resolved}'`,
            );
            source.path = resolved;
            source.name = path.basename(resolved);
            if (source.sourceReference !== undefined) {
                source.sourceReference = 0;
            }
        }
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

/** Timeout for waiting for iwasm's debug server to start listening. */
const WAMR_DEBUG_SERVER_READY_TIMEOUT_MS = 10_000;

/**
 * Waits for the iwasm process to print "Debug server listening on"
 * on stdout/stderr, indicating the GDB remote stub is ready for
 * connections.  This matches the IntelliJ plugin's readiness
 * detection in `WasmDebugRunner.prepareWamrSession()`.
 *
 * Resolves when the message is detected **or** when the timeout
 * elapses (matching IntelliJ's behavior of proceeding anyway).
 * Rejects only if the process exits or fails to start before
 * the server is ready.
 */
function waitForIwasmReady(
    iwasmProcess: ChildProcess,
    timeoutMs: number,
): Promise<void> {
    const log = getDebugOutputChannel();
    log.appendLine(
        `[Hexana] Waiting for iwasm to print debug server ready message ` +
        `(timeout=${timeoutMs}ms)`,
    );

    return new Promise<void>((resolve, reject) => {
        let settled = false;
        const collectedOutput: string[] = [];

        function settle(fn: () => void): void {
            if (settled) return;
            settled = true;
            cleanup();
            fn();
        }

        function onData(chunk: Buffer): void {
            const text = chunk.toString();
            collectedOutput.push(text);
            // Check accumulated output (not just this chunk) because
            // Node.js may split the ready message across chunks.
            const accumulated = collectedOutput.join("");
            if (
                accumulated.includes("Debug server listening on") ||
                accumulated.includes("debug server listening on")
            ) {
                log.appendLine(
                    `[Hexana] WAMR debug server is ready`,
                );
                settle(() => resolve());
            }
        }

        function onExit(code: number | null, signal: string | null): void {
            const output = collectedOutput.join("").trim();
            let details = `iwasm exited before debug server started ` +
                `(code=${code}, signal=${signal}).`;
            if (extensionLib?.isWamrDebugNotSupported(output)) {
                details += "\n\nThe installed iwasm does not support " +
                    "the \"-g\" debug option. Rebuild iwasm with:\n" +
                    "  cmake .. -DWAMR_BUILD_DEBUG_INTERP=1\n" +
                    "  make";
            } else if (output) {
                details += `\n\niwasm output:\n${output}`;
            }
            settle(() => reject(new Error(details)));
        }

        function onError(err: Error): void {
            settle(() => reject(
                new Error(`iwasm failed to start: ${err.message}`),
            ));
        }

        function cleanup(): void {
            clearTimeout(timer);
            iwasmProcess.stdout?.off("data", onData);
            iwasmProcess.stderr?.off("data", onData);
            iwasmProcess.off("exit", onExit);
            iwasmProcess.off("error", onError);
        }

        const timer = setTimeout(() => {
            const output = collectedOutput.join("").trim();
            // Match IntelliJ's behavior: on timeout, proceed with
            // the LLDB connection anyway.  The `process connect`
            // command in launchCommands will fail gracefully if
            // the server isn't actually ready.
            log.appendLine(
                `[Hexana] Timed out after ${timeoutMs}ms waiting for ` +
                `iwasm debug server ready message. ` +
                `Proceeding with LLDB connection anyway.` +
                (output ? `\n  iwasm output so far: ${output}` : ""),
            );
            settle(() => resolve());
        }, timeoutMs);

        iwasmProcess.stdout?.on("data", onData);
        iwasmProcess.stderr?.on("data", onData);
        iwasmProcess.on("exit", onExit);
        iwasmProcess.on("error", onError);
    });
}

/**
 * Launches the iwasm process with GDB remote stub enabled.
 *
 * Returns the iwasm child process and the debug connection address,
 * or `undefined` if the launch failed (error is shown to the user).
 *
 * Note: the caller is responsible for waiting until the debug
 * server is ready (via `waitForIwasmReady()`) before sending
 * `process connect` to LLDB.
 */
function launchIwasmDebugServer(
    config: vscode.DebugConfiguration,
): { iwasmProcess: ChildProcess; debugHost: string; debugPort: number } | undefined {
    if (!extensionLib) {
        vscode.window.showErrorMessage("Cannot debug: extension library is not available.");
        return undefined;
    }

    const wasmFilePath = config.wasmFile as string;
    const wasmBytes = readWasmBytes(wasmFilePath);
    const programArgs = config.programArgs || "";
    const runtimeOptions = config.runtimeOptions || "";
    const exportName = (config.exportName || "") as string;

    const isWindows = os.platform() === "win32";
    const iwasmCmd = getRuntimeCommand("wamr", isWindows);
    if (!iwasmCmd) {
        vscode.window.showErrorMessage(
            "Cannot debug: iwasm executable not found. " +
            "Install WAMR (iwasm) or set 'hexana.wamrPath' in settings.",
        );
        return undefined;
    }

    const debugCmdLine = extensionLib.buildWamrDebugCommandLine(
        wasmFilePath,
        wasmBytes,
        programArgs,
        runtimeOptions,
        exportName,
        "127.0.0.1",
        1234,
        nodeStubWriter,
    );

    const log = getDebugOutputChannel();
    const iwasmArgs = [...debugCmdLine.arguments];
    const cwd = debugCmdLine.workingDirectory || path.dirname(wasmFilePath);

    log.appendLine(
        `[Hexana] Starting iwasm debug server: ${iwasmCmd} ${iwasmArgs.join(" ")} (cwd=${cwd})`,
    );

    const iwasmProcess = spawn(iwasmCmd, iwasmArgs, {
        cwd,
        stdio: ["pipe", "pipe", "pipe"],
    });

    iwasmProcess.stdout?.on("data", (chunk: Buffer) => {
        log.appendLine(`[iwasm stdout] ${chunk.toString().trimEnd()}`);
    });
    iwasmProcess.stderr?.on("data", (chunk: Buffer) => {
        log.appendLine(`[iwasm stderr] ${chunk.toString().trimEnd()}`);
    });
    iwasmProcess.on("error", (err) => {
        log.appendLine(`[iwasm ERROR] ${err.message}`);
    });
    iwasmProcess.on("exit", (code, signal) => {
        log.appendLine(`[iwasm EXIT] code=${code}, signal=${signal}`);
    });

    return {
        iwasmProcess,
        debugHost: debugCmdLine.debugHost,
        debugPort: debugCmdLine.debugPort,
    };
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

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
        // Linux: discover lldb-dap dynamically instead of
        // maintaining a hardcoded version list.

        // 1. /usr/bin/lldb-dap* (e.g. lldb-dap-22, lldb-dap-18)
        try {
            const usrBinEntries = fs.readdirSync("/usr/bin")
                .filter((f) => f.startsWith("lldb-dap"))
                .sort()
                .reverse(); // prefer highest version
            for (const entry of usrBinEntries) {
                candidates.push(`/usr/bin/${entry}`);
            }
        } catch {
            // /usr/bin not readable — unlikely but safe to skip
        }

        // 2. /usr/lib/llvm*/bin/lldb-dap
        try {
            const usrLibEntries = fs.readdirSync("/usr/lib")
                .filter((f) => f.startsWith("llvm"))
                .sort()
                .reverse(); // prefer highest version
            for (const entry of usrLibEntries) {
                candidates.push(`/usr/lib/${entry}/bin/lldb-dap`);
            }
        } catch {
            // /usr/lib not readable — skip
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
 * Resolves the Chrome/Chromium executable path from settings,
 * environment, or well-known locations.
 */
function resolveChromePath(configPath?: string): string | null {
    const custom = (configPath || "").trim();
    if (custom && fs.existsSync(custom)) {
        return custom;
    }

    const settingsPath = vscode.workspace.getConfiguration("hexana")
        .get<string>("chromePath", "").trim();
    if (settingsPath && fs.existsSync(settingsPath)) {
        return settingsPath;
    }

    const candidates: string[] = [];
    if (os.platform() === "darwin") {
        candidates.push(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
        );
    } else if (os.platform() === "win32") {
        const pf = process.env.ProgramFiles || "C:\\Program Files";
        const pfx86 = process.env["ProgramFiles(x86)"] || "C:\\Program Files (x86)";
        const localAppData = process.env.LOCALAPPDATA || "";
        candidates.push(
            `${pf}\\Google\\Chrome\\Application\\chrome.exe`,
            `${pfx86}\\Google\\Chrome\\Application\\chrome.exe`,
        );
        if (localAppData) {
            candidates.push(`${localAppData}\\Google\\Chrome\\Application\\chrome.exe`);
        }
    } else {
        candidates.push(
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/snap/bin/chromium",
        );
    }

    for (const c of candidates) {
        if (fs.existsSync(c)) {
            return c;
        }
    }
    return null;
}

/**
 * Resolves the Node.js executable path from settings or PATH.
 */
function resolveNodePath(configPath?: string): string | null {
    const custom = (configPath || "").trim();
    if (custom) {
        return custom;
    }

    const settingsPath = vscode.workspace.getConfiguration("hexana")
        .get<string>("nodePath", "").trim();
    if (settingsPath) {
        return settingsPath;
    }

    const isWindows = os.platform() === "win32";
    const exeName = isWindows ? "node.exe" : "node";
    try {
        const whichCmd = isWindows ? "where.exe" : "which";
        const result = execFileSync(whichCmd, [exeName], { stdio: "pipe" }).toString().trim();
        if (result) {
            return result.split("\n")[0].trim();
        }
    } catch {
        // Not on PATH
    }

    if (!isWindows) {
        const candidates = ["/usr/local/bin/node", "/usr/bin/node"];
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
                // DAP setBreakpoints sends the complete breakpoint
                // list for a source, so replace (not append) the
                // line set to avoid stale entries.
                const bpLines = msg.arguments.breakpoints as Array<{ line: number }> | undefined;
                if (bpLines && bpLines.length > 0) {
                    const lineSet = new Set<number>();
                    for (const bp of bpLines) {
                        lineSet.add(bp.line);
                    }
                    this.registeredBreakpoints.set(baseName, lineSet);
                } else {
                    this.registeredBreakpoints.delete(baseName);
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
            const stderrText = chunk.toString();
            this.log.appendLine(`[lldb-dap stderr] ${stderrText}`);
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
                // Normalise to forward slashes so suffix matching
                // works on Windows where paths use backslashes.
                const normFull = fullPath.replace(/\\/g, "/");
                if (normFull.endsWith(suffix) || normFull === normalised) {
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

// ---- WebSocket import for CDP communication ----
// Node.js built-in WebSocket (available since Node 21 / VS Code 1.95+).
// Falls back to the `ws` npm package if available.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const WebSocketImpl: any = (() => { try { return require("ws"); } catch { return (globalThis as any).WebSocket; } })();

/**
 * Regex matching WebSocket debug URLs printed by Node.js and Chrome.
 */
const WS_URL_REGEX = /\bws:\/\/(\[[0-9a-fA-F:]+\]|[\w.-]+):?(\d+)?(?:\/([\w\-./]*))?/;

/**
 * Parses a Wasm binary to extract the byte offset of each function
 * body's first instruction in the code section.
 *
 * V8 uses these byte offsets (relative to the start of the module)
 * as `columnNumber` in CDP breakpoint locations for `wasm://` scripts.
 *
 * Returns a sorted array of absolute byte offsets into the module.
 */
function parseWasmFunctionOffsets(buf: Buffer): number[] {
    const offsets: number[] = [];
    let pos = 0;

    // Wasm magic + version (8 bytes)
    if (buf.length < 8) return offsets;
    pos = 8;

    // Read LEB128 unsigned
    function readU32Leb128(): number {
        let result = 0;
        let shift = 0;
        while (pos < buf.length) {
            const byte = buf[pos++];
            result |= (byte & 0x7f) << shift;
            if ((byte & 0x80) === 0) break;
            shift += 7;
        }
        return result >>> 0;
    }

    // Scan sections to find the code section (id = 10)
    while (pos < buf.length) {
        const sectionId = buf[pos++];
        const sectionSize = readU32Leb128();
        const sectionEnd = pos + sectionSize;

        if (sectionId === 10) {
            // Code section: count of function bodies
            const funcCount = readU32Leb128();
            for (let i = 0; i < funcCount; i++) {
                const bodySize = readU32Leb128();
                const bodyStart = pos;
                // Skip local declarations to find the first instruction
                const localDeclCount = readU32Leb128();
                for (let j = 0; j < localDeclCount; j++) {
                    readU32Leb128(); // count
                    readU32Leb128(); // type
                }
                // pos now points to the first instruction byte
                offsets.push(pos);
                // Advance to the next function body
                pos = bodyStart + bodySize;
            }
            break;
        }

        pos = sectionEnd;
    }

    return offsets;
}

/**
 * An inline debug adapter that communicates with Node.js or Chrome
 * via the Chrome DevTools Protocol (CDP) over WebSocket, translating
 * DAP (Debug Adapter Protocol) messages to/from CDP commands.
 *
 * Mirrors the IntelliJ plugin's `CdpDebugProcess` implementation.
 *
 * Supports:
 * - Breakpoints (source-level, mapped via DWARF when available)
 * - Stepping (over/into/out), pause, continue
 * - Call stack inspection
 * - Scope variable inspection (with wasmvalue flattening)
 * - Expression evaluation on call frames
 */
class CdpDebugAdapter implements vscode.DebugAdapter {
    private readonly emitter = new vscode.EventEmitter<vscode.DebugProtocolMessage>();
    readonly onDidSendMessage: vscode.Event<vscode.DebugProtocolMessage> = this.emitter.event;

    private readonly log = getDebugOutputChannel();
    private ws: any;
    private cdpId = 1;
    private readonly cdpPending = new Map<number, (result: any) => void>();
    private readonly cdpEvents = new Map<string, Array<(params: any) => void>>();
    /** CDP scripts keyed by scriptId. */
    private readonly scripts = new Map<string, { scriptId: string; url: string }>();
    /** The scriptId of the first wasm:// script seen. */
    private wasmScriptId: string | undefined;
    /** Byte offsets of each function body in the wasm code section. */
    private wasmFunctionOffsets: number[] = [];
    /** DAP seq counter for messages sent to VS Code. */
    private dapSeq = 1;
    /** Maps CDP breakpoint IDs to DAP breakpoint info. */
    private readonly breakpoints = new Map<string, { id: number; source: any; line: number }>();
    /** Tracks wasm byte offsets that already have a CDP breakpoint, to avoid duplicates. */
    private readonly wasmBreakpointOffsets = new Map<number, string>();
    private nextBpId = 1;
    /** Stored pause call frames from the last Debugger.paused event. */
    private pausedFrames: any[] | undefined;
    /** Hit breakpoint IDs from the last Debugger.paused event. */
    private pausedHitBreakpoints: string[] = [];
    /** Whether the last DAP action was "continue" (not step). */
    private lastActionWasContinue = false;
    /** CDP breakpoint IDs that were hit at the last stop reported to DAP. */
    private lastStoppedBreakpoints = new Set<string>();
    /** Whether at least one wasm:// script has been seen. */
    private wasmScriptSeen = false;
    /** The child process (Node.js or Chrome). */
    private childProcess: ChildProcess | undefined;
    /** HTTP server for browser mode. */
    private httpServer: import("http").Server | undefined;
    /** Temp Chrome user-data dir. */
    private tempUserDataDir: string | undefined;
    /** Whether the adapter has been disposed. */
    private disposed = false;
    /** Thread ID used in DAP (single-threaded). */
    private readonly THREAD_ID = 1;
    /** Tracks whether we've sent the initialized event. */
    private initialized = false;
    /** Whether configurationDone has been received (breakpoints are set). */
    private configurationDone = false;
    /** Pending breakpoint requests received before wasm script was parsed. */
    private readonly pendingSetBreakpoints: Array<{ msg: any; resolve: () => void }> = [];
    /** Serialization queue for DAP requests to prevent concurrent handling. */
    private dapRequestQueue: Promise<void> = Promise.resolve();
    /** Resolves when the CDP WebSocket connection is established. */
    private cdpReadyResolve!: () => void;
    private readonly cdpReady = new Promise<void>(resolve => {
        this.cdpReadyResolve = resolve;
    });

    constructor(
        private readonly runtime: "nodejs" | "browser",
        private readonly config: vscode.DebugConfiguration,
    ) {}

    // ---- DAP message handling (from VS Code) ----

    handleMessage(message: vscode.DebugProtocolMessage): void {
        const msg = message as any;
        if (msg.type === "request") {
            // Serialize all DAP requests so concurrent setBreakpoints
            // calls don't race (e.g. two source files snapping to the
            // same wasm offset).
            this.dapRequestQueue = this.dapRequestQueue.then(
                () => this.handleDapRequest(msg),
            ).catch(err => {
                this.sendDapResponse(msg, false, String(err));
            });
        }
    }

    private async handleDapRequest(msg: any): Promise<void> {
        switch (msg.command) {
            case "initialize":
                this.sendDapResponse(msg, true, undefined, {
                    supportsConfigurationDoneRequest: true,
                    supportsFunctionBreakpoints: false,
                    supportsConditionalBreakpoints: false,
                    supportsEvaluateForHovers: true,
                    supportsStepBack: false,
                    supportsSetVariable: false,
                    supportsRestartFrame: false,
                    supportsGotoTargetsRequest: false,
                    supportsCompletionsRequest: false,
                });
                this.sendDapEvent("initialized", {});
                break;

            case "launch":
                await this.handleLaunch(msg);
                break;

            case "configurationDone":
                this.configurationDone = true;
                this.sendDapResponse(msg, true);
                // Resume the target. Wasm breakpoints were already
                // registered via setBreakpointByUrl with urlRegex
                // (using pre-parsed function offsets), so V8 will
                // apply them when the wasm:// script appears.
                await this.cdpSend("Runtime.runIfWaitingForDebugger").catch(() => {});
                break;

            case "setBreakpoints":
                await this.handleSetBreakpoints(msg);
                break;

            case "threads":
                this.sendDapResponse(msg, true, undefined, {
                    threads: [{ id: this.THREAD_ID, name: "Wasm Thread" }],
                });
                break;

            case "stackTrace":
                await this.handleStackTrace(msg);
                break;

            case "scopes":
                await this.handleScopes(msg);
                break;

            case "variables":
                await this.handleVariables(msg);
                break;

            case "continue":
                this.lastActionWasContinue = true;
                await this.cdpSend("Debugger.resume");
                this.sendDapResponse(msg, true, undefined, { allThreadsContinued: true });
                break;

            case "next":
                this.lastActionWasContinue = false;
                await this.cdpSend("Debugger.stepOver");
                this.sendDapResponse(msg, true);
                break;

            case "stepIn":
                this.lastActionWasContinue = false;
                await this.cdpSend("Debugger.stepInto");
                this.sendDapResponse(msg, true);
                break;

            case "stepOut":
                this.lastActionWasContinue = false;
                await this.cdpSend("Debugger.stepOut");
                this.sendDapResponse(msg, true);
                break;

            case "pause":
                await this.cdpSend("Debugger.pause");
                this.sendDapResponse(msg, true);
                break;

            case "evaluate":
                await this.handleEvaluate(msg);
                break;

            case "disconnect":
                this.sendDapResponse(msg, true);
                this.shutdown();
                break;

            default:
                this.sendDapResponse(msg, true);
                break;
        }
    }

    // ---- Launch ----

    private async handleLaunch(msg: any): Promise<void> {
        try {
            // Parse the wasm binary to find function body offsets before
            // launching, so we can set breakpoints via urlRegex before
            // the wasm script is loaded by V8.
            const wasmFile = this.config.wasmFile;
            if (wasmFile && fs.existsSync(wasmFile)) {
                this.wasmFunctionOffsets = parseWasmFunctionOffsets(fs.readFileSync(wasmFile));
                this.log.appendLine(
                    `[Hexana CDP] Parsed ${this.wasmFunctionOffsets.length} function offset(s) from ${wasmFile}`,
                );
            }

            const wsUrl = this.runtime === "nodejs"
                ? await this.launchNodeJs()
                : await this.launchBrowser();

            await this.connectCdp(wsUrl);
            this.sendDapResponse(msg, true);
        } catch (e) {
            this.sendDapResponse(msg, false, `Launch failed: ${e}`);
        }
    }

    private launchNodeJs(): Promise<string> {
        return new Promise((resolve, reject) => {
            const nodePath = resolveNodePath(this.config.nodePath);
            if (!nodePath) {
                reject(new Error(
                    "Node.js not found. Install Node.js or set 'hexana.nodePath' in settings.",
                ));
                return;
            }

            const wasmFile = this.config.wasmFile;
            if (!wasmFile || !fs.existsSync(wasmFile)) {
                reject(new Error(`Wasm file not found: ${wasmFile}`));
                return;
            }

            // Build the inline script (same as shared buildNodeJsRunArgs)
            const exportName = (this.config.exportName || "_start").trim();
            const wasmPath = wasmFile.replace(/\\/g, "\\\\").replace(/'/g, "\\'");
            const programArgs = (this.config.programArgs || "").trim();
            const argsArray = programArgs
                ? programArgs.split(/\s+/).map((a: string) => `"${a.replace(/"/g, '\\"')}"`).join(",")
                : "";

            const script = [
                "const fs=require('fs');",
                `const buf=fs.readFileSync('${wasmPath}');`,
                "(async()=>{",
                "const mod=await WebAssembly.compile(buf);",
                "const imps=WebAssembly.Module.imports(mod);",
                "const importObj={};",
                "for(const i of imps){",
                "if(!importObj[i.module])importObj[i.module]={};",
                "if(i.kind==='function'){",
                "if(i.name==='proc_exit')importObj[i.module][i.name]=(c)=>process.exit(c);",
                "else importObj[i.module][i.name]=()=>0;",
                "}else if(i.kind==='memory'){",
                "importObj[i.module][i.name]=new WebAssembly.Memory({initial:1});",
                "}else if(i.kind==='table'){",
                "importObj[i.module][i.name]=new WebAssembly.Table({initial:1,element:'anyfunc'});",
                "}else if(i.kind==='global'){",
                "importObj[i.module][i.name]=new WebAssembly.Global({value:'i32',mutable:true},0);",
                "}}",
                "const instance=await WebAssembly.instantiate(mod,importObj);",
                `const fn=instance.exports['${exportName}'];`,
                `if(!fn)throw new Error('Export not found: ${exportName}');`,
                argsArray ? `const r=fn(${argsArray});` : "const r=fn();",
                "if(r!==undefined)console.log(r);",
                "})()",
            ].join("");

            const args = ["--inspect-brk=0", "--eval", script];
            const workDir = this.config.cwd || path.dirname(wasmFile);

            this.log.appendLine(`[Hexana CDP] Launching: ${nodePath} ${args.join(" ")}`);
            const child = spawn(nodePath, args, {
                cwd: workDir,
                stdio: ["pipe", "pipe", "pipe"],
            });
            this.childProcess = child;

            let resolved = false;
            const onData = (chunk: Buffer) => {
                const text = chunk.toString();
                this.log.appendLine(`[Hexana CDP] stderr: ${text.trim()}`);
                if (!resolved) {
                    const match = WS_URL_REGEX.exec(text);
                    if (match) {
                        resolved = true;
                        resolve(match[0]);
                    }
                }
            };
            child.stderr?.on("data", onData);
            child.stdout?.on("data", (chunk: Buffer) => {
                this.sendDapEvent("output", {
                    category: "stdout",
                    output: chunk.toString(),
                });
            });
            child.on("error", (err) => {
                if (!resolved) { resolved = true; reject(err); }
            });
            child.on("exit", (code) => {
                if (!resolved) { resolved = true; reject(new Error(`Node.js exited with code ${code}`)); }
                this.sendDapEvent("terminated", {});
            });

            setTimeout(() => {
                if (!resolved) { resolved = true; reject(new Error("Timeout waiting for Node.js debug URL")); }
            }, 10000);
        });
    }

    private launchBrowser(): Promise<string> {
        return new Promise((resolve, reject) => {
            const chromePath = resolveChromePath(this.config.chromePath);
            if (!chromePath) {
                reject(new Error(
                    "Chrome/Chromium not found. Install Chrome or set 'hexana.chromePath' in settings.",
                ));
                return;
            }

            const wasmFile = this.config.wasmFile;
            if (!wasmFile || !fs.existsSync(wasmFile)) {
                reject(new Error(`Wasm file not found: ${wasmFile}`));
                return;
            }

            // Start HTTP server
            const http = require("http") as typeof import("http");
            const wasmFileName = path.basename(wasmFile);
            const exportName = (this.config.exportName || "_start").trim();
            const programArgs = (this.config.programArgs || "").trim();
            const wasmBytes = fs.readFileSync(wasmFile);

            let htmlContent: string;
            if (extensionLib) {
                htmlContent = extensionLib.generateBrowserHtml(wasmFileName, exportName, programArgs);
            } else {
                htmlContent = `<!DOCTYPE html><html><body><script>
                    (async()=>{const r=await fetch('${wasmFileName}');const b=await r.arrayBuffer();
                    const m=await WebAssembly.compile(b);const i=await WebAssembly.instantiate(m,{});
                    const fn=i.exports['${exportName}'];if(fn)console.log(fn());})();
                    </script></body></html>`;
            }

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

            server.listen(0, "127.0.0.1", () => {
                const addr = server.address() as import("net").AddressInfo;
                this.httpServer = server;
                const pageUrl = `http://127.0.0.1:${addr.port}/`;

                const tempDir = path.join(os.tmpdir(), `hexana-chrome-${Date.now()}`);
                fs.mkdirSync(tempDir, { recursive: true });
                this.tempUserDataDir = tempDir;

                const chromeArgs = [
                    "--remote-debugging-port=0",
                    "--no-first-run",
                    "--no-default-browser-check",
                    `--user-data-dir=${tempDir}`,
                    pageUrl,
                ];

                this.log.appendLine(`[Hexana CDP] Launching: ${chromePath} ${chromeArgs.join(" ")}`);
                const child = spawn(chromePath, chromeArgs, { stdio: ["pipe", "pipe", "pipe"] });
                this.childProcess = child;

                let resolved = false;
                const onData = (chunk: Buffer) => {
                    const text = chunk.toString();
                    this.log.appendLine(`[Hexana CDP] Chrome: ${text.trim()}`);
                    if (!resolved) {
                        const match = WS_URL_REGEX.exec(text);
                        if (match) {
                            resolved = true;
                            // Need to discover page-level WS URL
                            this.discoverPageWsUrl(match[0]).then(
                                (pageWs) => resolve(pageWs),
                                (err) => reject(err),
                            );
                        }
                    }
                };
                child.stderr?.on("data", onData);
                child.stdout?.on("data", onData);
                child.on("error", (err) => {
                    if (!resolved) { resolved = true; reject(err); }
                });
                child.on("exit", (code) => {
                    if (!resolved) { resolved = true; reject(new Error(`Chrome exited with code ${code}`)); }
                });

                setTimeout(() => {
                    if (!resolved) { resolved = true; reject(new Error("Timeout waiting for Chrome debug URL")); }
                }, 15000);
            });

            server.on("error", reject);
        });
    }

    /**
     * Discovers the page-level WebSocket URL from Chrome's /json endpoint.
     * Matches IntelliJ's `discoverPageWebSocketUrl`.
     */
    private async discoverPageWsUrl(browserWsUrl: string): Promise<string> {
        const http = require("http") as typeof import("http");
        const url = require("url") as typeof import("url");
        const parsed = new URL(browserWsUrl.replace("ws://", "http://"));
        const host = parsed.hostname;
        const port = parsed.port;

        for (let attempt = 0; attempt < 10; attempt++) {
            try {
                const body = await new Promise<string>((resolve, reject) => {
                    http.get(`http://${host}:${port}/json`, (res) => {
                        let data = "";
                        res.on("data", (chunk: string) => data += chunk);
                        res.on("end", () => resolve(data));
                    }).on("error", reject);
                });
                const targets = JSON.parse(body) as any[];
                for (const t of targets) {
                    if (t.type === "page" && t.webSocketDebuggerUrl) {
                        return t.webSocketDebuggerUrl;
                    }
                }
            } catch {
                // retry
            }
            await new Promise(r => setTimeout(r, 500));
        }
        throw new Error("Failed to discover Chrome page debug target");
    }

    // ---- CDP WebSocket ----

    private connectCdp(wsUrl: string): Promise<void> {
        return new Promise((resolve, reject) => {
            this.log.appendLine(`[Hexana CDP] Connecting to ${wsUrl}`);
            const ws = new WebSocketImpl(wsUrl);
            this.ws = ws;

            ws.onopen = () => {
                this.log.appendLine("[Hexana CDP] Connected");
                this.initCdpSession().then(() => {
                    this.cdpReadyResolve();
                    resolve();
                }, reject);
            };
            ws.onmessage = (event: any) => {
                const data = typeof event === "string" ? event : (event.data ?? event);
                this.handleCdpMessage(typeof data === "string" ? data : data.toString());
            };
            ws.onclose = () => {
                this.log.appendLine("[Hexana CDP] Disconnected");
                if (!this.disposed) {
                    this.sendDapEvent("terminated", {});
                }
            };
            ws.onerror = (err: any) => {
                const msg = err?.message || String(err);
                this.log.appendLine(`[Hexana CDP] Error: ${msg}`);
                reject(new Error(`CDP connection failed: ${msg}`));
            };
        });
    }

    private async initCdpSession(): Promise<void> {
        // Register event handlers
        this.onCdp("Debugger.paused", (params) => this.handlePaused(params));
        this.onCdp("Debugger.resumed", () => {
            this.sendDapEvent("continued", { threadId: this.THREAD_ID, allThreadsContinued: true });
        });
        this.onCdp("Debugger.scriptParsed", (params) => {
            const scriptId = params.scriptId as string;
            const url = params.url as string || "";
            this.scripts.set(scriptId, { scriptId, url });
            if (url.startsWith("wasm://")) {
                this.wasmScriptSeen = true;
                if (!this.wasmScriptId) {
                    this.wasmScriptId = scriptId;
                    this.log.appendLine(`[Hexana CDP] Wasm script parsed: scriptId=${scriptId} url=${url}`);
                }
                this.retryPendingBreakpoints();
            }
        });
        this.onCdp("Runtime.executionContextDestroyed", () => {
            this.cdpSendFire("Debugger.disable");
            this.cdpSendFire("Runtime.disable");
            this.closeCdp();
        });
        this.onCdp("Runtime.consoleAPICalled", (params) => {
            const args = (params.args || []) as any[];
            const text = args.map((a: any) => a.value ?? a.description ?? "").join(" ");
            if (text) {
                this.sendDapEvent("output", { category: "console", output: text + "\n" });
            }
        });

        await this.cdpSend("Runtime.enable");
        await this.cdpSend("Debugger.enable");
        // Do NOT call Runtime.runIfWaitingForDebugger here.
        // It will be called in configurationDone, after VS Code has sent setBreakpoints.
    }

    private handleCdpMessage(text: string): void {
        let json: any;
        try { json = JSON.parse(text); } catch { return; }

        if (json.id !== undefined) {
            const cb = this.cdpPending.get(json.id);
            if (cb) {
                this.cdpPending.delete(json.id);
                if (json.error) {
                    cb({ __error: json.error.message || "CDP error" });
                } else {
                    cb(json.result || {});
                }
            }
            return;
        }

        const method = json.method as string;
        const params = json.params || {};
        const listeners = this.cdpEvents.get(method);
        if (listeners) {
            for (const fn of listeners) {
                try { fn(params); } catch { /* ignore */ }
            }
        }
    }

    private cdpSend(method: string, params: any = {}): Promise<any> {
        return new Promise((resolve, reject) => {
            if (!this.ws) { reject(new Error("Not connected")); return; }
            const id = this.cdpId++;
            this.cdpPending.set(id, (result) => {
                if (result?.__error) { reject(new Error(result.__error)); }
                else { resolve(result); }
            });
            this.ws.send(JSON.stringify({ id, method, params }));
        });
    }

    private cdpSendFire(method: string, params: any = {}): void {
        if (!this.ws) return;
        const id = this.cdpId++;
        try { this.ws.send(JSON.stringify({ id, method, params })); } catch { /* ignore */ }
    }

    private onCdp(method: string, fn: (params: any) => void): void {
        let list = this.cdpEvents.get(method);
        if (!list) { list = []; this.cdpEvents.set(method, list); }
        list.push(fn);
    }

    private closeCdp(): void {
        try { this.ws?.close(); } catch { /* ignore */ }
    }

    // ---- Debugger.paused handler ----

    private handlePaused(params: any): void {
        const callFrames = params.callFrames as any[] || [];
        const hitBreakpoints = params.hitBreakpoints as string[] || [];
        const reason = params.reason as string;

        if (callFrames.length === 0) return;

        // Auto-resume "Break on start" (from --inspect-brk) only after
        // configurationDone. Breakpoints are set via setBreakpointByUrl
        // with urlRegex before the wasm script loads, so they are
        // already registered by the time we resume.
        if (reason === "Break on start" && hitBreakpoints.length === 0) {
            if (this.configurationDone) {
                this.cdpSend("Debugger.resume").catch(() => {});
            }
            // If configurationDone hasn't arrived yet, the target stays
            // paused and will be resumed by configurationDone's
            // Runtime.runIfWaitingForDebugger call.
            return;
        }

        // Check if any wasm frames exist
        const hasWasm = callFrames.some((f: any) => {
            const sid = f.location?.scriptId;
            const info = sid ? this.scripts.get(sid) : undefined;
            return info?.url?.startsWith("wasm://");
        });

        // Auto-resume pauses that don't match any of our breakpoints
        // and aren't the result of a user-initiated step.
        const hasOurBp = hitBreakpoints.some(id => this.breakpoints.has(id));

        // Pure JS pause after wasm loaded — auto-resume
        if (this.wasmScriptSeen && !hasWasm && !hasOurBp) {
            this.cdpSend("Debugger.resume").catch(() => {});
            return;
        }

        // Wasm pause with no matching breakpoint and not a step —
        // likely a function-boundary pause (entry/exit). Auto-resume
        // so the user doesn't see a spurious stop after "Continue".
        if (hasWasm && !hasOurBp && reason !== "step" && reason !== "debugCommand") {
            this.cdpSend("Debugger.resume").catch(() => {});
            return;
        }

        // If the user pressed "Continue" and we hit exactly the same
        // set of CDP breakpoints again, auto-resume.  This happens when
        // a wasm helper (e.g. FromWasmAbi) is called multiple times in
        // a row for successive arguments — V8 fires the same breakpoint
        // for each invocation.
        if (this.lastActionWasContinue && hasOurBp) {
            const currentBpSet = new Set(hitBreakpoints.filter(id => this.breakpoints.has(id)));
            if (
                currentBpSet.size > 0 &&
                currentBpSet.size === this.lastStoppedBreakpoints.size &&
                [...currentBpSet].every(id => this.lastStoppedBreakpoints.has(id))
            ) {
                this.cdpSend("Debugger.resume").catch(() => {});
                return;
            }
        }

        this.lastActionWasContinue = false;
        this.pausedFrames = callFrames;
        this.pausedHitBreakpoints = hitBreakpoints;
        this.lastStoppedBreakpoints = new Set(hitBreakpoints.filter(id => this.breakpoints.has(id)));

        const dapReason = hasOurBp ? "breakpoint" : "step";
        this.sendDapEvent("stopped", {
            reason: dapReason,
            threadId: this.THREAD_ID,
            allThreadsStopped: true,
        });
    }

    // ---- DAP: setBreakpoints ----

    private async handleSetBreakpoints(msg: any): Promise<void> {
        const args = msg.arguments || {};
        const source = args.source || {};
        const sourcePath = source.path as string || "";
        const breakpointLines = (args.breakpoints || []) as any[];
        const isWasmSource = sourcePath.endsWith(".wasm");

        this.log.appendLine(
            `[Hexana CDP] handleSetBreakpoints: source=${sourcePath} lines=[${breakpointLines.map((b: any) => b.line).join(",")}] wasmScriptId=${this.wasmScriptId ?? "none"}`,
        );

        // Wait for the CDP connection to be established — setBreakpoints
        // may arrive from VS Code before handleLaunch finishes connecting.
        await this.cdpReady;

        // When the wasm script is already loaded, query V8 for the
        // precise set of valid breakpoint byte offsets.
        // We always populate these for all source types because V8
        // doesn't support file-URL breakpoints for compiled Wasm
        // (no DWARF source-map integration). The wasmBreakpointOffsets
        // dedup map prevents duplicate CDP breakpoints when multiple
        // source files snap to the same offset.
        let validOffsets: number[] = [];
        if (this.wasmScriptId) {
            try {
                const result = await this.cdpSend("Debugger.getPossibleBreakpoints", {
                    start: { scriptId: this.wasmScriptId, lineNumber: 0, columnNumber: 0 },
                });
                const locs = (result.locations || []) as Array<{ lineNumber: number; columnNumber: number }>;
                validOffsets = locs.map((loc) => loc.columnNumber).sort((a, b) => a - b);
                this.log.appendLine(
                    `[Hexana CDP] getPossibleBreakpoints returned ${validOffsets.length} location(s)`,
                );
            } catch (e) {
                this.log.appendLine(`[Hexana CDP] getPossibleBreakpoints failed: ${e}`);
            }
        }

        // When the wasm script is NOT yet loaded, fall back to the
        // pre-parsed function entry offsets from the binary.
        if (validOffsets.length === 0 && this.wasmFunctionOffsets.length > 0) {
            validOffsets = this.wasmFunctionOffsets;
            this.log.appendLine(
                `[Hexana CDP] Using ${validOffsets.length} pre-parsed function offset(s)`,
            );
        }

        const results: any[] = [];
        for (const bp of breakpointLines) {
            const line = bp.line as number;

            // For .wasm sources the DAP line IS the byte offset;
            // for other sources (Rust, C, Go) we don't have a
            // DWARF→offset mapping yet, so we use the line as-is
            // and let findNearestOffset snap to the closest valid
            // instruction.
            const requestedOffset = line;

            // Strategy 1: wasm script already loaded — use
            // Debugger.setBreakpoint with the exact scriptId and
            // the nearest valid byte offset.
            if (this.wasmScriptId && validOffsets.length > 0) {
                const offset = this.findNearestOffset(validOffsets, requestedOffset);
                if (offset !== undefined) {
                    // Skip if we already have a CDP breakpoint at this offset.
                    // For non-.wasm sources this means another file already
                    // claimed this offset — report unverified so the unrelated
                    // file doesn't appear to have a working breakpoint.
                    const existingBpId = this.wasmBreakpointOffsets.get(offset);
                    if (existingBpId) {
                        if (isWasmSource) {
                            const dapId = this.nextBpId++;
                            results.push({ id: dapId, verified: true, line });
                            this.log.appendLine(
                                `[Hexana CDP] Reusing existing wasm breakpoint at offset ${offset} (requested ${requestedOffset}, source=${sourcePath})`,
                            );
                        } else {
                            results.push({ verified: false, line, message: "Breakpoint offset already claimed by another source" });
                            this.log.appendLine(
                                `[Hexana CDP] Skipping duplicate wasm offset ${offset} for non-wasm source ${sourcePath}`,
                            );
                        }
                        continue;
                    }
                    try {
                        const cdpResult = await this.cdpSend("Debugger.setBreakpoint", {
                            location: {
                                scriptId: this.wasmScriptId,
                                lineNumber: 0,
                                columnNumber: offset,
                            },
                        });
                        const bpId = cdpResult.breakpointId;
                        if (bpId) {
                            const dapId = this.nextBpId++;
                            this.breakpoints.set(bpId, { id: dapId, source, line });
                            this.wasmBreakpointOffsets.set(offset, bpId);
                            results.push({ id: dapId, verified: true, line });
                            this.log.appendLine(
                                `[Hexana CDP] Set wasm breakpoint at offset ${offset} (requested ${requestedOffset}, source=${sourcePath})`,
                            );
                            continue;
                        }
                    } catch (e) {
                        this.log.appendLine(`[Hexana CDP] Failed to set wasm breakpoint at offset ${offset}: ${e}`);
                    }
                }
            }

            // Strategy 2: wasm script NOT yet loaded — use
            // Debugger.setBreakpointByUrl with urlRegex so V8
            // applies the breakpoint when the wasm:// script appears.
            if (!this.wasmScriptId && validOffsets.length > 0) {
                const offset = this.findNearestOffset(validOffsets, requestedOffset);
                if (offset !== undefined) {
                    // Skip if we already have a CDP breakpoint at this offset.
                    const existingBpId = this.wasmBreakpointOffsets.get(offset);
                    if (existingBpId) {
                        if (isWasmSource) {
                            const dapId = this.nextBpId++;
                            results.push({ id: dapId, verified: true, line });
                            this.log.appendLine(
                                `[Hexana CDP] Reusing existing wasm breakpoint at offset ${offset} (requested ${requestedOffset}, source=${sourcePath})`,
                            );
                        } else {
                            results.push({ verified: false, line, message: "Breakpoint offset already claimed by another source" });
                            this.log.appendLine(
                                `[Hexana CDP] Skipping duplicate wasm offset ${offset} for non-wasm source ${sourcePath}`,
                            );
                        }
                        continue;
                    }
                    try {
                        const cdpResult = await this.cdpSend("Debugger.setBreakpointByUrl", {
                            urlRegex: "^wasm://",
                            lineNumber: 0,
                            columnNumber: offset,
                        });
                        const bpId = cdpResult.breakpointId;
                        if (bpId) {
                            const dapId = this.nextBpId++;
                            this.breakpoints.set(bpId, { id: dapId, source, line });
                            this.wasmBreakpointOffsets.set(offset, bpId);
                            results.push({ id: dapId, verified: true, line });
                            this.log.appendLine(
                                `[Hexana CDP] Set wasm breakpoint by URL at offset ${offset} (requested ${requestedOffset}, source=${sourcePath})`,
                            );
                            continue;
                        }
                    } catch (e) {
                        this.log.appendLine(`[Hexana CDP] Failed to set wasm breakpoint by URL at offset ${offset}: ${e}`);
                    }
                }
            }

            // Strategy 3: No valid wasm offsets available — try a
            // file-URL breakpoint (works for JS sources or if V8
            // ever gains DWARF source-map support).
            if (sourcePath && !isWasmSource) {
                try {
                    const fileUrl = sourcePath.startsWith("/")
                        ? `file://${sourcePath}`
                        : `file:///${sourcePath.replace(/\\/g, "/")}`;
                    const cdpResult = await this.cdpSend("Debugger.setBreakpointByUrl", {
                        lineNumber: line - 1, // DAP is 1-based, CDP is 0-based
                        url: fileUrl,
                        columnNumber: 0,
                    });
                    const bpId = cdpResult.breakpointId;
                    if (bpId) {
                        const dapId = this.nextBpId++;
                        this.breakpoints.set(bpId, { id: dapId, source, line });
                        results.push({ id: dapId, verified: true, line });
                        this.log.appendLine(
                            `[Hexana CDP] Set source breakpoint at ${sourcePath}:${line}`,
                        );
                        continue;
                    }
                } catch {
                    // ignore
                }
            }

            results.push({ verified: false, line, message: "Could not set breakpoint" });
        }

        this.sendDapResponse(msg, true, undefined, { breakpoints: results });
    }

    /**
     * Finds the nearest valid byte offset at or after the requested offset
     * using binary search on the sorted array of valid offsets.
     */
    private findNearestOffset(sortedOffsets: number[], requested: number): number | undefined {
        let lo = 0;
        let hi = sortedOffsets.length;
        while (lo < hi) {
            const mid = (lo + hi) >>> 1;
            if (sortedOffsets[mid] < requested) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        // Return the offset at or just after the requested position,
        // or the last valid offset if requested is past all of them.
        if (lo < sortedOffsets.length) return sortedOffsets[lo];
        if (sortedOffsets.length > 0) return sortedOffsets[sortedOffsets.length - 1];
        return undefined;
    }

    private async retryPendingBreakpoints(): Promise<void> {
        const pending = this.pendingSetBreakpoints.splice(0);
        if (pending.length === 0) return;

        this.log.appendLine(`[Hexana CDP] Retrying ${pending.length} deferred breakpoint request(s)`);
        for (const { msg, resolve } of pending) {
            await this.handleSetBreakpoints(msg);
            resolve();
        }
    }

    // ---- DAP: stackTrace ----

    private async handleStackTrace(msg: any): Promise<void> {
        const frames = this.pausedFrames || [];
        const stackFrames = frames.map((f: any, i: number) => {
            const loc = f.location || {};
            const scriptId = loc.scriptId as string;
            const info = scriptId ? this.scripts.get(scriptId) : undefined;
            const url = info?.url || "";
            let lineNumber = (loc.lineNumber as number || 0) + 1; // CDP 0-based → DAP 1-based
            let columnNumber = (loc.columnNumber as number || 0) + 1;
            const name = (f.functionName as string) || "<anonymous>";

            const source: any = {};
            if (url.startsWith("wasm://")) {
                // Try to map wasm:// frames back to the original source
                // file (Rust, C, Go) that was used when setting the
                // breakpoint, so VS Code opens the real source editor.
                let mappedToSource = false;
                if (i === 0) {
                    // Top frame: check hit breakpoints for original source info
                    for (const bpId of this.pausedHitBreakpoints) {
                        const bp = this.breakpoints.get(bpId);
                        if (bp?.source?.path && !bp.source.path.endsWith(".wasm")) {
                            source.name = path.basename(bp.source.path);
                            source.path = bp.source.path;
                            // Use the original breakpoint line in the source file
                            // instead of the wasm byte offset.
                            lineNumber = bp.line;
                            columnNumber = 1;
                            mappedToSource = true;
                            break;
                        }
                    }
                }
                if (!mappedToSource) {
                    // Fall back to the .wasm file on disk
                    const wasmFile = this.config.wasmFile as string | undefined;
                    if (wasmFile) {
                        source.name = path.basename(wasmFile);
                        source.path = wasmFile;
                    } else {
                        source.name = url;
                    }
                }
            } else if (url.startsWith("file://")) {
                let p = url.replace("file://", "");
                if (p.length >= 3 && p[0] === "/" && p[2] === ":") p = p.substring(1);
                source.name = path.basename(p);
                source.path = p;
            } else if (url) {
                source.name = url;
            }

            return {
                id: i,
                name,
                source: Object.keys(source).length > 0 ? source : undefined,
                line: lineNumber,
                column: columnNumber,
            };
        });

        this.sendDapResponse(msg, true, undefined, {
            stackFrames,
            totalFrames: stackFrames.length,
        });
    }

    // ---- DAP: scopes ----

    private async handleScopes(msg: any): Promise<void> {
        const frameId = msg.arguments?.frameId ?? 0;
        const frame = this.pausedFrames?.[frameId];
        if (!frame) {
            this.sendDapResponse(msg, true, undefined, { scopes: [] });
            return;
        }

        const scopeChain = (frame.scopeChain || []) as any[];
        const scopes = scopeChain
            .filter((s: any) => s.type !== "global" && s.type !== "module")
            .map((s: any, i: number) => ({
                name: s.type === "local" ? "Locals" : (s.name || s.type || "Scope"),
                variablesReference: this.encodeVarRef(frameId, i),
                expensive: false,
            }));

        this.sendDapResponse(msg, true, undefined, { scopes });
    }

    // ---- DAP: variables ----

    private async handleVariables(msg: any): Promise<void> {
        const varRef = msg.arguments?.variablesReference ?? 0;
        const variables: any[] = [];

        if (varRef >= 1_000_000) {
            // Object expansion
            const objectId = this.objectIdFromRef(varRef);
            if (objectId) {
                try {
                    const result = await this.cdpSend("Runtime.getProperties", {
                        objectId,
                        ownProperties: true,
                    });
                    for (const prop of (result.result || [])) {
                        const val = prop.value || {};
                        variables.push(this.cdpValueToVariable(prop.name, val));
                    }
                } catch { /* ignore */ }
            }
        } else {
            // Scope variables
            const { frameId, scopeIdx } = this.decodeVarRef(varRef);
            const frame = this.pausedFrames?.[frameId];
            if (frame) {
                const scopeChain = (frame.scopeChain || []) as any[];
                const filteredScopes = scopeChain.filter((s: any) => s.type !== "global" && s.type !== "module");
                const scope = filteredScopes[scopeIdx];
                if (scope?.object?.objectId) {
                    try {
                        const result = await this.cdpSend("Runtime.getProperties", {
                            objectId: scope.object.objectId,
                            ownProperties: true,
                        });
                        for (const prop of (result.result || [])) {
                            const val = prop.value || {};
                            variables.push(this.cdpValueToVariable(prop.name, val));
                        }
                    } catch { /* ignore */ }
                }
            }
        }

        this.sendDapResponse(msg, true, undefined, { variables });
    }

    private cdpValueToVariable(name: string, val: any): any {
        const type = val.type || "unknown";
        const subtype = val.subtype;
        let value: string;
        let variablesReference = 0;

        if (subtype === "wasmvalue") {
            value = val.description || "wasmvalue";
        } else if (val.value !== undefined) {
            value = String(val.value);
        } else if (val.description) {
            value = val.description;
        } else {
            value = type;
        }

        if (val.objectId && (type === "object" || type === "function")) {
            variablesReference = this.storeObjectRef(val.objectId);
        }

        return { name, value, type: subtype || type, variablesReference };
    }

    // ---- DAP: evaluate ----

    private async handleEvaluate(msg: any): Promise<void> {
        const expression = msg.arguments?.expression || "";
        const frameId = msg.arguments?.frameId;
        const frame = frameId !== undefined ? this.pausedFrames?.[frameId] : undefined;

        try {
            let result: any;
            if (frame?.callFrameId) {
                result = await this.cdpSend("Debugger.evaluateOnCallFrame", {
                    callFrameId: frame.callFrameId,
                    expression,
                    returnByValue: false,
                });
            } else {
                result = await this.cdpSend("Runtime.evaluate", {
                    expression,
                    returnByValue: false,
                });
            }

            const resultObj = result.result || {};
            if (result.exceptionDetails) {
                this.sendDapResponse(msg, false, result.exceptionDetails.text || "Evaluation error");
            } else {
                let value = resultObj.value !== undefined ? String(resultObj.value) : (resultObj.description || resultObj.type || "undefined");
                let variablesReference = 0;
                if (resultObj.objectId && (resultObj.type === "object" || resultObj.type === "function")) {
                    variablesReference = this.storeObjectRef(resultObj.objectId);
                }
                this.sendDapResponse(msg, true, undefined, {
                    result: value,
                    type: resultObj.subtype || resultObj.type,
                    variablesReference,
                });
            }
        } catch (e) {
            this.sendDapResponse(msg, false, String(e));
        }
    }

    // ---- Variable reference encoding ----

    private encodeVarRef(frameId: number, scopeIdx: number): number {
        return (frameId + 1) * 1000 + scopeIdx + 1;
    }

    private decodeVarRef(ref: number): { frameId: number; scopeIdx: number } {
        const frameId = Math.floor(ref / 1000) - 1;
        const scopeIdx = (ref % 1000) - 1;
        return { frameId, scopeIdx };
    }

    private readonly objectRefs = new Map<number, string>();
    private nextObjRef = 1_000_000;

    private storeObjectRef(objectId: string): number {
        const ref = this.nextObjRef++;
        this.objectRefs.set(ref, objectId);
        return ref;
    }

    private objectIdFromRef(ref: number): string | undefined {
        return this.objectRefs.get(ref);
    }

    // ---- DAP message helpers ----

    private sendDapResponse(request: any, success: boolean, message?: string, body?: any): void {
        const response: any = {
            seq: this.dapSeq++,
            type: "response",
            request_seq: request.seq,
            command: request.command,
            success,
        };
        if (message) response.message = message;
        if (body) response.body = body;
        this.emitter.fire(response);
    }

    private sendDapEvent(event: string, body: any): void {
        this.emitter.fire({
            seq: this.dapSeq++,
            type: "event",
            event,
            body,
        } as any);
    }

    // ---- Cleanup ----

    private shutdown(): void {
        if (this.disposed) return;
        this.disposed = true;
        this.closeCdp();
        if (this.childProcess && !this.childProcess.killed) {
            this.childProcess.kill();
        }
        if (this.httpServer) {
            this.httpServer.close();
        }
        if (this.tempUserDataDir) {
            try { fs.rmSync(this.tempUserDataDir, { recursive: true, force: true }); } catch { /* ignore */ }
        }
    }

    dispose(): void {
        this.shutdown();
        this.emitter.dispose();
    }
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
    const invokeArgs = (config.invokeArgs || "") as string;

    // The webview sends `invokeArgs` as a ready-made wasmtime CLI
    // fragment, e.g. `--invoke add` for core modules or
    // `--invoke "calculate(add, 0, 0)"` for components.  The run
    // path passes this as `runtimeOptions` so it gets spliced into
    // the argument list by `buildWasmtimeCommandLine`.  We do the
    // same here: combine user-supplied `runtimeOptions` with
    // `invokeArgs` so the debug command line matches the run one.
    const combinedOptions = [runtimeOptions, invokeArgs]
        .map((s) => s.trim())
        .filter(Boolean)
        .join(" ");

    const isWindows = os.platform() === "win32";
    const wasmtimeCmd = getRuntimeCommand("wasmtime", isWindows);
    if (!wasmtimeCmd) {
        throw new Error("Cannot resolve wasmtime executable");
    }

    const effectiveWasmPath = (config.effectiveWasmPath || null) as string | null;

    const debugCmdLine = extensionLib.buildWasmtimeDebugCommandLine(
        wasmFilePath,
        wasmBytes,
        programArgs,
        combinedOptions,
        effectiveWasmPath,
    );

    return {
        program: wasmtimeCmd,
        args: [...debugCmdLine.arguments],
        cwd: debugCmdLine.workingDirectory || path.dirname(wasmFilePath),
    };
}

/**
 * Writes a Python helper script for the Windows JIT loader
 * workaround to a temporary file and returns its path.
 *
 * The script defines `jit_breakpoint_callback(frame, bp_loc, dict)`
 * which is invoked by LLDB when `__jit_debug_register_code` is hit.
 * It reads the `__jit_debug_descriptor` structure from inferior
 * memory, extracts the JIT-compiled ELF object, loads it into LLDB,
 * scans its line tables for addresses matching pending (unresolved)
 * source breakpoints, and sets address breakpoints for any matches.
 *
 * The callback returns `False` to auto-continue execution so the
 * process only stops at resolved user breakpoints.  After 3
 * invocations (matching IntelliJ's MAX_JIT_REGISTER_ATTEMPTS), the
 * helper breakpoint is deleted to avoid further overhead.
 *
 * Returns `undefined` if the file could not be written.
 */
function writeWindowsJitLoaderScript(): string | undefined {
    try {
        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "hexana-jit-"));
        const scriptPath = path.join(tmpDir, "hexana_jit_loader.py");
        // The script defines two things:
        // 1. A breakpoint callback `jit_breakpoint_callback` that
        //    reads the JIT descriptor, loads the ELF object, scans
        //    line tables, and sets address breakpoints.
        // 2. A module-level `__lldb_init_module` function that
        //    automatically sets the breakpoint on
        //    __jit_debug_register_code and attaches the callback
        //    when the script is imported via `command script import`.
        //    This avoids the race where lldb-dap creates its own
        //    breakpoints between our `breakpoint set` and
        //    `breakpoint command add` preRunCommands.
        const script = `
import struct
import os
import re
import tempfile
import lldb

_hexana_jit_attempt = 0
_hexana_jit_max_attempts = 3
_hexana_jit_resolved = False
_hexana_jit_bp_id = None

def _get_bp_file_line(bp):
    """Extract the requested file and line from an LLDB breakpoint."""
    stream = lldb.SBStream()
    bp.GetDescription(stream)
    desc = stream.GetData() or ''
    m = re.search(r"file\\s*=\\s*'([^']+)'\\s*,\\s*line\\s*=\\s*(\\d+)", desc)
    if m:
        return (m.group(1), int(m.group(2)))
    return None

def jit_breakpoint_callback(frame, bp_loc, extra_args, internal_dict):
    """Breakpoint callback invoked when __jit_debug_register_code is hit.

    Reads the JIT descriptor from inferior memory, extracts the
    JIT-compiled ELF object, loads it into LLDB, scans its line
    tables for addresses matching pending source breakpoints, and
    sets address breakpoints for any matches.

    Returns False to auto-continue execution.
    """
    global _hexana_jit_attempt, _hexana_jit_resolved, _hexana_jit_bp_id
    _hexana_jit_attempt += 1
    print('[hexana-jit] callback attempt %d' % _hexana_jit_attempt)

    if _hexana_jit_resolved or _hexana_jit_attempt > _hexana_jit_max_attempts:
        if _hexana_jit_bp_id is not None:
            bp_loc.GetBreakpoint().GetTarget().BreakpointDelete(_hexana_jit_bp_id)
            _hexana_jit_bp_id = None
        return False

    t = frame.GetThread().GetProcess().GetTarget()
    p = frame.GetThread().GetProcess()
    e = t.GetByteOrder()
    ps = t.GetAddressByteSize()
    bo = '<' if e == lldb.eByteOrderLittle else '>'
    pfmt = bo + ('Q' if ps == 8 else 'I')

    # Find __jit_debug_descriptor
    syms = [s for s in t.FindSymbols('__jit_debug_descriptor')]
    desc_addr = 0
    if syms:
        desc_addr = syms[0].GetSymbol().GetStartAddress().GetLoadAddress(t)
    else:
        gvs = t.FindGlobalVariables('__jit_debug_descriptor', 1)
        if gvs.GetSize() > 0:
            gv = gvs.GetValueAtIndex(0)
            if gv:
                desc_addr = gv.GetLoadAddress()

    if not desc_addr or desc_addr == 0xFFFFFFFFFFFFFFFF:
        print('[hexana-jit] descriptor not found')
        return False

    err = lldb.SBError()
    desc_bytes = p.ReadMemory(desc_addr, 4 + 4 + ps + ps, err)
    if not err.Success() or len(desc_bytes) < 4 + 4 + ps + ps:
        print('[hexana-jit] failed to read descriptor: %s' % err)
        return False

    rel_entry = struct.unpack_from(pfmt, desc_bytes, 8)[0]
    first_entry = struct.unpack_from(pfmt, desc_bytes, 8 + ps)[0]
    print('[hexana-jit] rel_entry=0x%x first_entry=0x%x' % (rel_entry, first_entry))
    if rel_entry == 0 and first_entry != 0:
        rel_entry = first_entry

    if rel_entry == 0:
        print('[hexana-jit] no entry pointer')
        return False

    entry_bytes = p.ReadMemory(rel_entry, ps + ps + ps + 8, err)
    if not err.Success() or len(entry_bytes) < ps + ps + ps + 8:
        print('[hexana-jit] failed to read entry: %s' % err)
        return False

    sym_addr = struct.unpack_from(pfmt, entry_bytes, ps + ps)[0]
    sym_size = struct.unpack_from(bo + 'Q', entry_bytes, ps + ps + ps)[0]
    print('[hexana-jit] sym_addr=0x%x sym_size=%d' % (sym_addr, sym_size))
    if sym_addr == 0 or sym_size == 0 or sym_size >= 100 * 1024 * 1024:
        print('[hexana-jit] invalid sym_addr/sym_size')
        return False

    obj_bytes = p.ReadMemory(sym_addr, sym_size, err)
    if not err.Success() or len(obj_bytes) != sym_size:
        print('[hexana-jit] failed to read object: %s' % err)
        return False

    # Write the JIT object to a temp file
    td = tempfile.mkdtemp(prefix='hexana-jit-obj-')
    fp = os.path.join(td, 'jit_obj_' + hex(sym_addr) + '.elf')
    with open(fp, 'wb') as f:
        f.write(obj_bytes)
    print('[hexana-jit] wrote %s (%d bytes)' % (fp, sym_size))

    # Load the module into LLDB
    mod = t.AddModule(fp, '', None)
    if not mod or not mod.IsValid():
        print('[hexana-jit] failed to load module')
        return False

    # Set section load addresses
    for i in range(mod.GetNumSections()):
        sec = mod.GetSectionAtIndex(i)
        t.SetSectionLoadAddress(sec, sym_addr + sec.GetFileOffset())

    # Collect pending (unresolved) source breakpoints
    helper_bp_id = _hexana_jit_bp_id or bp_loc.GetBreakpoint().GetID()
    pending = []
    for bi in range(t.GetNumBreakpoints()):
        bp = t.GetBreakpointAtIndex(bi)
        if bp.GetID() == helper_bp_id:
            continue
        if bp.GetNumLocations() > 0:
            continue
        fl = _get_bp_file_line(bp)
        if fl:
            basename = fl[0].replace('\\\\', '/').rsplit('/', 1)[-1]
            pending.append((basename, fl[1]))
    print('[hexana-jit] pending breakpoints: %s' % pending)

    if not pending:
        for bi in range(t.GetNumBreakpoints()):
            bp = t.GetBreakpointAtIndex(bi)
            if bp.GetID() == helper_bp_id:
                continue
            if bp.GetNumLocations() > 0:
                _hexana_jit_resolved = True
                t.BreakpointDelete(helper_bp_id)
                _hexana_jit_bp_id = None
                print('[hexana-jit] breakpoints auto-resolved after module load')
                return False
        return False

    # Scan the JIT module's line tables for matching addresses
    found = []
    for ci in range(mod.GetNumCompileUnits()):
        cu = mod.GetCompileUnitAtIndex(ci)
        nle = cu.GetNumLineEntries()
        print('[hexana-jit] cu[%d] entries=%d file=%s' % (ci, nle, cu.GetFileSpec()))
        for li in range(nle):
            le = cu.GetLineEntryAtIndex(li)
            fs = le.GetFileSpec()
            if not fs.IsValid():
                continue
            fn = fs.GetFilename()
            ln = le.GetLine()
            addr = le.GetStartAddress().GetLoadAddress(t)
            if addr == lldb.LLDB_INVALID_ADDRESS:
                continue
            for (bf, bl) in pending:
                if fn == bf and ln == bl and addr not in found:
                    found.append(addr)

    for addr in found:
        print('[hexana-jit] setting address breakpoint at 0x%x' % addr)
        t.BreakpointCreateByAddress(addr)

    if found:
        _hexana_jit_resolved = True
        t.BreakpointDelete(helper_bp_id)
        _hexana_jit_bp_id = None
        print('[hexana-jit] resolved %d address breakpoints' % len(found))
    else:
        # Fallback: scan ALL modules for matching line entries
        print('[hexana-jit] no matches in JIT module, scanning all %d modules' % t.GetNumModules())
        for mi in range(t.GetNumModules()):
            m = t.GetModuleAtIndex(mi)
            for ci in range(m.GetNumCompileUnits()):
                cu = m.GetCompileUnitAtIndex(ci)
                for li in range(cu.GetNumLineEntries()):
                    le = cu.GetLineEntryAtIndex(li)
                    fs = le.GetFileSpec()
                    if not fs.IsValid():
                        continue
                    fn = fs.GetFilename()
                    ln = le.GetLine()
                    addr = le.GetStartAddress().GetLoadAddress(t)
                    if addr == lldb.LLDB_INVALID_ADDRESS:
                        continue
                    for (bf, bl) in pending:
                        if fn == bf and ln == bl and addr not in found:
                            found.append(addr)
        for addr in found:
            print('[hexana-jit] (fallback) setting address breakpoint at 0x%x' % addr)
            t.BreakpointCreateByAddress(addr)
        if found:
            _hexana_jit_resolved = True
            t.BreakpointDelete(helper_bp_id)
            _hexana_jit_bp_id = None
            print('[hexana-jit] (fallback) resolved %d address breakpoints' % len(found))

        # Also check if LLDB auto-resolved any existing breakpoints
        if not found:
            for bi in range(t.GetNumBreakpoints()):
                bp = t.GetBreakpointAtIndex(bi)
                if bp.GetID() == helper_bp_id:
                    continue
                if bp.GetNumLocations() > 0:
                    _hexana_jit_resolved = True
                    t.BreakpointDelete(helper_bp_id)
                    _hexana_jit_bp_id = None
                    print('[hexana-jit] breakpoints auto-resolved (fallback)')
                    break

    return False

def __lldb_init_module(debugger, internal_dict):
    """Called automatically when the script is imported via
    'command script import'.  Sets the breakpoint on
    __jit_debug_register_code and attaches the callback
    atomically, avoiding races with lldb-dap's own breakpoint
    management."""
    global _hexana_jit_bp_id
    target = debugger.GetSelectedTarget()
    bp = target.BreakpointCreateByName('__jit_debug_register_code')
    if bp.IsValid():
        _hexana_jit_bp_id = bp.GetID()
        bp.SetAutoContinue(True)
        bp.SetScriptCallbackFunction('hexana_jit_loader.jit_breakpoint_callback')
        print('[hexana-jit] breakpoint %d set on __jit_debug_register_code' % bp.GetID())
    else:
        print('[hexana-jit] WARNING: failed to set breakpoint on __jit_debug_register_code')
`;
        fs.writeFileSync(scriptPath, script, "utf-8");
        return scriptPath;
    } catch {
        return undefined;
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

import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { execFileSync, spawn, ChildProcess } from "child_process";
import { extensionLib } from "./WasmRunConfigurations";
import { getDebugOutputChannel, readWasmBytes } from "./WasmDebugAdapter";
import type { SourceLocationResolver } from "./extension-lib";

/**
 * Resolves the Chrome/Chromium executable path from settings,
 * environment, or well-known locations.
 */
export function resolveChromePath(configPath?: string): string | null {
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
export function resolveNodePath(configPath?: string): string | null {
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

// ---- WebSocket import for CDP communication ----
// Node.js built-in WebSocket (available since Node 21 / VS Code 1.95+).
// Falls back to the `ws` npm package if available.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const WebSocketImpl: any = (() => { try { return require("ws"); } catch { return (globalThis as any).WebSocket; } })();

/**
 * Regex matching WebSocket debug URLs printed by Node.js and Chrome.
 */
const WS_URL_REGEX = /\bws:\/\/(\[[0-9a-fA-F:]+\]|[\w.-]+):?(\d+)?(?:\/([\w\-./]*))?/;
const WASM_VAR_REGEX = /^\$var(\d+)$/;

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
export class CdpDebugAdapter implements vscode.DebugAdapter {
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
    /** DWARF-based source ↔ Wasm offset resolver (null when no DWARF info). */
    private sourceLocationResolver: SourceLocationResolver | undefined;
    /** DAP seq counter for messages sent to VS Code. */
    private dapSeq = 1;
    /** Maps CDP breakpoint IDs to DAP breakpoint info. */
    private readonly breakpoints = new Map<string, { id: number; source: any; line: number; condition?: string }>();
    /** Tracks wasm byte offsets that already have a CDP breakpoint, to avoid duplicates. */
    private readonly wasmBreakpointOffsets = new Map<number, string>();
    private nextBpId = 1;
    /** Stored pause call frames from the last Debugger.paused event. */
    private pausedFrames: any[] | undefined;
    /** Hit breakpoint IDs from the last Debugger.paused event. */
    private pausedHitBreakpoints: string[] = [];
    /**
     * Maps each DAP stackFrame id to the index of the underlying CDP call
     * frame in pausedFrames. Synthetic inlined frames map to the same CDP
     * frame index as their physical parent so that scopes/evaluate work.
     */
    private frameIdToCdpFrameIndex: number[] = [];
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
    /** Stores the original setBreakpoints DAP messages for re-registration. */
    private readonly originalBreakpointMessages: any[] = [];
    /** Whether we are currently inside reRegisterBreakpointsAndResume. */
    private reRegistering = false;
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
                    supportsConditionalBreakpoints: true,
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
                const wasmBuf = fs.readFileSync(wasmFile);
                this.wasmFunctionOffsets = parseWasmFunctionOffsets(wasmBuf);
                this.log.appendLine(
                    `[Hexana CDP] Parsed ${this.wasmFunctionOffsets.length} function offset(s) from ${wasmFile}`,
                );

                // Try to create a DWARF-based source location resolver
                if (extensionLib) {
                    try {
                        const wasmInt8 = new Int8Array(wasmBuf.buffer, wasmBuf.byteOffset, wasmBuf.byteLength);
                        this.sourceLocationResolver = extensionLib.SourceLocationResolver.create(wasmInt8) ?? undefined;
                        if (this.sourceLocationResolver) {
                            const files = this.sourceLocationResolver.knownSourceFiles();
                            this.log.appendLine(
                                `[Hexana CDP] DWARF source location resolver created with ${files.length} source file(s): ${files.join(", ")}`,
                            );
                        } else {
                            this.log.appendLine("[Hexana CDP] No DWARF line info found in wasm binary");
                        }
                    } catch (e) {
                        this.log.appendLine(`[Hexana CDP] Failed to create source location resolver: ${e}`);
                    }
                }
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

        // Set an instrumentation breakpoint so V8 pauses BEFORE
        // executing any newly compiled script — including wasm modules.
        // This guarantees we can set scriptId-based breakpoints before
        // any wasm code runs (urlRegex breakpoints do NOT work for wasm).
        try {
            await this.cdpSend("Debugger.setInstrumentationBreakpoint", {
                instrumentation: "beforeScriptExecution",
            });
            this.log.appendLine("[Hexana CDP] Instrumentation breakpoint set for beforeScriptExecution");
        } catch (e) {
            this.log.appendLine(`[Hexana CDP] Failed to set instrumentation breakpoint: ${e}`);
        }

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

        // V8 instrumentation breakpoint: pauses before executing a
        // newly compiled script. For wasm scripts, re-register
        // breakpoints with the concrete scriptId and then resume.
        if (reason === "instrumentation") {
            // Check if this pause is for a wasm script (the one we care about).
            // Non-wasm instrumentation pauses (JS scripts) should just resume.
            const pausedScriptId = callFrames[0]?.location?.scriptId as string | undefined;
            const pausedScript = pausedScriptId ? this.scripts.get(pausedScriptId) : undefined;
            const isWasmInstrumentation = pausedScript?.url?.startsWith("wasm://") ?? false;
            this.log.appendLine(
                `[Hexana CDP] Instrumentation pause — isWasm=${isWasmInstrumentation} wasmScriptId=${this.wasmScriptId ?? "none"}`,
            );
            if (isWasmInstrumentation && this.wasmScriptId && this.originalBreakpointMessages.length > 0) {
                this.reRegisterBreakpointsAndResume();
            } else {
                this.cdpSend("Debugger.resume").catch(() => {});
            }
            return;
        }

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
        this.frameIdToCdpFrameIndex = []; // rebuilt in handleStackTrace
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
            // Condition passed through raw (no DWARF name translation on VS Code
            // side: the local-name map requires a function name that is not known
            // at breakpoint-set time; DWARF name translation is only available on
            // the IntelliJ side where the resolver has offset-based lookup).
            const condition: string | undefined = (bp.condition && bp.condition.trim())
                ? bp.condition.trim()
                : undefined;

            // For non-.wasm sources (Rust, C, Go), use the DWARF
            // resolver to map source file + line → Wasm byte offsets.
            // For .wasm sources the DAP line IS the byte offset.
            // Pass the full sourcePath so the resolver can apply
            // path-suffix matching for multi-crate projects where
            // several files share the same name (lib.rs, mod.rs, etc.).
            let dwarfOffsets: number[] | undefined;
            if (!isWasmSource && this.sourceLocationResolver) {
                // DAP lines are 1-based; the resolver expects 0-based
                const resolvedArr = this.sourceLocationResolver.sourceToAllWasmOffsets(sourcePath, line - 1);
                if (resolvedArr && resolvedArr.length > 0) {
                    dwarfOffsets = Array.from(resolvedArr);
                    this.log.appendLine(
                        `[Hexana CDP] DWARF resolved ${sourcePath}:${line} → offsets [${dwarfOffsets.join(",")}]`,
                    );
                }
            }

            // When DWARF resolution produced offsets, set breakpoints
            // at each resolved offset (there may be multiple when the
            // same source line compiles into several wasm functions).
            if (dwarfOffsets && dwarfOffsets.length > 0) {
                let anySet = false;
                for (const offset of dwarfOffsets) {
                    // Snap to a valid V8 offset if possible
                    const snapped = validOffsets.length > 0
                        ? this.findNearestOffset(validOffsets, offset) ?? offset
                        : offset;

                    const existingBpId = this.wasmBreakpointOffsets.get(snapped);
                    if (existingBpId) {
                        this.log.appendLine(
                            `[Hexana CDP] Reusing existing wasm breakpoint at offset ${snapped} for ${sourcePath}:${line}`,
                        );
                        if (!anySet) {
                            const dapId = this.nextBpId++;
                            results.push({ id: dapId, verified: true, line });
                            anySet = true;
                        }
                        continue;
                    }

                    try {
                        const cdpResult = this.wasmScriptId
                            ? await this.cdpSend("Debugger.setBreakpoint", {
                                location: {
                                    scriptId: this.wasmScriptId,
                                    lineNumber: 0,
                                    columnNumber: snapped,
                                },
                                ...(condition !== undefined ? { condition } : {}),
                            })
                            : await this.cdpSend("Debugger.setBreakpointByUrl", {
                                urlRegex: "^wasm://",
                                lineNumber: 0,
                                columnNumber: snapped,
                                ...(condition !== undefined ? { condition } : {}),
                            });
                        const bpId = cdpResult.breakpointId;
                        if (bpId) {
                            const dapId = this.nextBpId++;
                            this.breakpoints.set(bpId, { id: dapId, source, line, condition });
                            this.wasmBreakpointOffsets.set(snapped, bpId);
                            if (!anySet) {
                                results.push({ id: dapId, verified: true, line });
                                anySet = true;
                            }
                            this.log.appendLine(
                                `[Hexana CDP] Set DWARF breakpoint at offset ${snapped} for ${sourcePath}:${line}`,
                            );
                        }
                    } catch (e) {
                        this.log.appendLine(`[Hexana CDP] Failed to set DWARF breakpoint at offset ${snapped}: ${e}`);
                    }
                }
                if (anySet) continue;
            }

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
                            ...(condition !== undefined ? { condition } : {}),
                        });
                        const bpId = cdpResult.breakpointId;
                        if (bpId) {
                            const dapId = this.nextBpId++;
                            this.breakpoints.set(bpId, { id: dapId, source, line, condition });
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
                            ...(condition !== undefined ? { condition } : {}),
                        });
                        const bpId = cdpResult.breakpointId;
                        if (bpId) {
                            const dapId = this.nextBpId++;
                            this.breakpoints.set(bpId, { id: dapId, source, line, condition });
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
                        ...(condition !== undefined ? { condition } : {}),
                    });
                    const bpId = cdpResult.breakpointId;
                    if (bpId) {
                        const dapId = this.nextBpId++;
                        this.breakpoints.set(bpId, { id: dapId, source, line, condition });
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

        // Store the original message for re-registration when the wasm
        // script appears (instrumentation breakpoint handler).
        // Skip during re-registration to avoid growing the list infinitely.
        if (!isWasmSource && this.sourceLocationResolver && !this.reRegistering) {
            this.originalBreakpointMessages.push(msg);
        }
    }

    /**
     * Finds the best matching workspace file path for a DWARF-reported
     * source path using longest-path-suffix matching.
     *
     * Strategy:
     * 1. Check registered breakpoint source paths for a suffix match,
     *    preferring the longest matching suffix (most path segments).
     * 2. If no breakpoint matches, check all workspace folders for a
     *    file whose path ends with the DWARF path suffix.
     * Falls back to `undefined` when no match is found.
     */
    private findWorkspaceFileByDwarfPath(dwarfPath: string): string | undefined {
        const normalizedDwarf = dwarfPath.replace(/\\/g, "/");
        const segments = normalizedDwarf.split("/").filter(s => s.length > 0);
        const baseName = segments[segments.length - 1] ?? "";

        let bestPath: string | undefined;
        let bestMatchLen = -1;

        // Helper: score a candidate path against the DWARF path segments.
        const score = (candidate: string): number => {
            const norm = candidate.replace(/\\/g, "/");
            for (let start = 0; start < segments.length; start++) {
                const suffix = segments.slice(start).join("/");
                if (norm.endsWith(suffix)) {
                    const prefixLen = norm.length - suffix.length;
                    if (prefixLen === 0 || norm[prefixLen - 1] === "/") {
                        return segments.length - start;
                    }
                }
            }
            return -1;
        };

        // Check breakpoint source paths first (cheapest, already in memory).
        for (const bp of this.breakpoints.values()) {
            const bpPath = bp.source?.path as string | undefined;
            if (!bpPath || path.basename(bpPath) !== baseName) continue;
            const s = score(bpPath);
            if (s > bestMatchLen) { bestMatchLen = s; bestPath = bpPath; }
        }
        if (bestMatchLen > 0) return bestPath;

        // Fall back to workspace folder scan.
        for (const folder of (vscode.workspace.workspaceFolders ?? [])) {
            const folderPath = folder.uri.fsPath.replace(/\\/g, "/");
            // Try building the full path by appending the DWARF path;
            // if the DWARF path is absolute this resolves correctly too.
            const candidate = folderPath + "/" + normalizedDwarf;
            if (require("fs").existsSync(candidate)) {
                const s = score(candidate);
                if (s > bestMatchLen) { bestMatchLen = s; bestPath = candidate; }
            }
        }
        return bestMatchLen > 0 ? bestPath : undefined;
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

    /**
     * Re-registers all breakpoints with the concrete wasm scriptId
     * and then resumes execution. Called from the instrumentation
     * breakpoint handler where V8 is paused BEFORE executing the
     * wasm script.
     */
    private async reRegisterBreakpointsAndResume(): Promise<void> {
        this.log.appendLine(
            `[Hexana CDP] Re-registering ${this.originalBreakpointMessages.length} breakpoint request(s) with scriptId=${this.wasmScriptId}`,
        );

        // Clear existing breakpoint state so re-registration starts fresh
        this.breakpoints.clear();
        this.wasmBreakpointOffsets.clear();

        this.reRegistering = true;
        try {
            for (const msg of this.originalBreakpointMessages) {
                await this.handleSetBreakpoints(msg);
            }
        } catch (e) {
            this.log.appendLine(`[Hexana CDP] Failed during instrumentation re-registration: ${e}`);
        } finally {
            this.reRegistering = false;
        }

        this.cdpSend("Debugger.resume").catch(() => {});
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
        const cdpFrames = this.pausedFrames || [];
        const stackFrames: any[] = [];
        // Rebuild the DAP-frame → CDP-frame-index mapping on each stackTrace response.
        this.frameIdToCdpFrameIndex = [];

        for (let cdpIdx = 0; cdpIdx < cdpFrames.length; cdpIdx++) {
            const f = cdpFrames[cdpIdx];
            const loc = f.location || {};
            const scriptId = loc.scriptId as string;
            const info = scriptId ? this.scripts.get(scriptId) : undefined;
            const url = info?.url || "";
            const wasmOffset = loc.columnNumber as number || 0;
            const name = (f.functionName as string) || "<anonymous>";

            if (url.startsWith("wasm://") && this.sourceLocationResolver) {
                // Look up the inlined call chain at this offset (innermost first).
                const inlinedChain: any[] = Array.from(
                    this.sourceLocationResolver.inlinedFramesAt(wasmOffset) as ArrayLike<any>,
                );

                if (inlinedChain.length > 0) {
                    // Emit synthetic inlined frames followed by the physical frame.
                    // chain[0] = innermost inlined call (paused position)
                    // chain[n-1] = outermost inlined call
                    // physical frame = shows the call site of chain[n-1]

                    for (let ci = 0; ci < inlinedChain.length; ci++) {
                        const inlined = inlinedChain[ci];
                        const inlinedName = (inlined.name ?? name) + " (inlined)";
                        const inlinedSource: any = {};
                        let inlinedLine = 1;

                        // innermost (ci === 0): show the physical pause position
                        // subsequent: show where the frame above was called from
                        const srcFile = ci === 0
                            ? this.sourceLocationResolver.wasmOffsetToSource(wasmOffset)?.file
                            : inlinedChain[ci - 1].callFile;
                        const srcLine = ci === 0
                            ? this.sourceLocationResolver.wasmOffsetToSource(wasmOffset)?.line
                            : (inlinedChain[ci - 1].callLine ?? undefined);

                        if (srcFile) {
                            const dwarfPath = (srcFile as string).replace(/\\/g, "/");
                            inlinedSource.name = path.basename(dwarfPath);
                            const fullPath = this.findWorkspaceFileByDwarfPath(dwarfPath);
                            if (fullPath) inlinedSource.path = fullPath;
                        }
                        if (srcLine != null) inlinedLine = srcLine as number;

                        const dapId = stackFrames.length;
                        this.frameIdToCdpFrameIndex.push(cdpIdx);
                        stackFrames.push({
                            id: dapId,
                            name: inlinedName,
                            source: Object.keys(inlinedSource).length > 0 ? inlinedSource : undefined,
                            line: inlinedLine,
                            column: 1,
                        });
                    }

                    // Physical frame: show call site of outermost inlined frame.
                    const outerCallFile = inlinedChain[inlinedChain.length - 1].callFile as string | undefined;
                    const outerCallLine = inlinedChain[inlinedChain.length - 1].callLine as number | undefined;
                    const physSource: any = {};
                    if (outerCallFile) {
                        const dp = outerCallFile.replace(/\\/g, "/");
                        physSource.name = path.basename(dp);
                        const fp = this.findWorkspaceFileByDwarfPath(dp);
                        if (fp) physSource.path = fp;
                    }
                    const physLine = outerCallLine ?? 1;
                    const physDapId = stackFrames.length;
                    this.frameIdToCdpFrameIndex.push(cdpIdx);
                    stackFrames.push({
                        id: physDapId,
                        name,
                        source: Object.keys(physSource).length > 0 ? physSource : undefined,
                        line: physLine,
                        column: 1,
                    });
                    continue;
                }
            }

            // No inlined frames (or not a wasm frame): resolve normally.
            let lineNumber = (loc.lineNumber as number || 0) + 1; // CDP 0-based → DAP 1-based
            let columnNumber = (loc.columnNumber as number || 0) + 1;
            const source: any = {};

            if (url.startsWith("wasm://")) {
                let mappedToSource = false;

                if (this.sourceLocationResolver) {
                    const resolved = this.sourceLocationResolver.wasmOffsetToSource(wasmOffset);
                    if (resolved) {
                        const resolvedDwarfPath = resolved.file.replace(/\\/g, "/");
                        const resolvedBaseName = path.basename(resolvedDwarfPath);
                        const fullPath = this.findWorkspaceFileByDwarfPath(resolvedDwarfPath);
                        source.name = resolvedBaseName;
                        if (fullPath) {
                            source.path = fullPath;
                        }
                        lineNumber = resolved.line;
                        columnNumber = 1;
                        mappedToSource = true;
                    }
                }

                // Fallback for top frame: check hit breakpoints
                if (!mappedToSource && cdpIdx === 0) {
                    for (const bpId of this.pausedHitBreakpoints) {
                        const bp = this.breakpoints.get(bpId);
                        if (bp?.source?.path && !bp.source.path.endsWith(".wasm")) {
                            source.name = path.basename(bp.source.path);
                            source.path = bp.source.path;
                            lineNumber = bp.line;
                            columnNumber = 1;
                            mappedToSource = true;
                            break;
                        }
                    }
                }
                if (!mappedToSource) {
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

            const dapId = stackFrames.length;
            this.frameIdToCdpFrameIndex.push(cdpIdx);
            stackFrames.push({
                id: dapId,
                name,
                source: Object.keys(source).length > 0 ? source : undefined,
                line: lineNumber,
                column: columnNumber,
            });
        }

        this.sendDapResponse(msg, true, undefined, {
            stackFrames,
            totalFrames: stackFrames.length,
        });
    }

    /** Resolves a DAP frameId to the underlying CDP call frame in pausedFrames. */
    private cdpFrameForDapId(frameId: number): any | undefined {
        const cdpIdx = this.frameIdToCdpFrameIndex[frameId];
        if (cdpIdx === undefined) return undefined;
        return this.pausedFrames?.[cdpIdx];
    }

    // ---- DAP: scopes ----

    private async handleScopes(msg: any): Promise<void> {
        const frameId = msg.arguments?.frameId ?? 0;
        const frame = this.cdpFrameForDapId(frameId);
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
                        variables.push(await this.cdpValueToVariable(prop.name, val));
                    }
                } catch { /* ignore */ }
            }
        } else {
            // Scope variables
            const { frameId, scopeIdx } = this.decodeVarRef(varRef);
            const frame = this.cdpFrameForDapId(frameId);
            if (frame) {
                const scopeChain = (frame.scopeChain || []) as any[];
                const filteredScopes = scopeChain.filter((s: any) => s.type !== "global" && s.type !== "module");
                const scope = filteredScopes[scopeIdx];
                if (scope?.object?.objectId) {
                    try {
                        const localToName = this.buildWasmLocalToNameMap(frame);
                        const hasDwarfMatch = this.sourceLocationResolver?.hasDwarfSubprogram(
                            (frame.functionName as string) || null,
                        ) ?? false;
                        const result = await this.cdpSend("Runtime.getProperties", {
                            objectId: scope.object.objectId,
                            ownProperties: true,
                        });
                        // Names of DWARF linear-memory variables at this offset: the
                        // positional wasm-local name fallback must not claim them (at
                        // -O0 it would label the shadow stack pointer with a variable
                        // name); the memory-read path renders their real values.
                        const memWasmOffset = (frame.location?.columnNumber as number) || 0;
                        const memNames = new Set<string>(
                            (this.sourceLocationResolver?.memoryVariablesAt(memWasmOffset) ?? [])
                                .map((v: any) => String(v.name)),
                        );
                        const wasmLocalValues = new Map<number, number>();
                        let localIndex = 0;
                        for (const prop of (result.result || [])) {
                            const rawName = String(prop.name ?? "");
                            const val = prop.value || {};
                            const subtype = val.subtype;

                            // Non-wasm scope artifacts (e.g. V8's "Stack" operand-stack
                            // object): keep the raw name. They are not wasm locals, so
                            // they consume no local index and get no DWARF name.
                            if (subtype !== "wasmvalue") {
                                variables.push(await this.cdpValueToVariable(rawName, val));
                                continue;
                            }

                            const displayName = this.resolveVariableDisplayName(rawName, localToName, localIndex);

                            // Collect numeric values from wasmvalue locals for frame-base resolution.
                            const varMatch = WASM_VAR_REGEX.exec(rawName);
                            if (varMatch) {
                                const idx = Number(varMatch[1]);
                                const innerStr = await this.resolveWasmValue(val);
                                const inner = innerStr !== undefined ? Number(innerStr) : NaN;
                                if (Number.isFinite(inner)) wasmLocalValues.set(idx, inner);
                            }

                            if (this.sourceLocationResolver && !hasDwarfMatch) {
                                localIndex++;
                                continue;
                            }
                            if (localToName.size > 0 && displayName === rawName) {
                                localIndex++;
                                continue;
                            }
                            // The DWARF name belongs to a linear-memory variable: the
                            // positional fallback mislabeled this wasm local.
                            if (displayName !== rawName && memNames.has(displayName)) {
                                localIndex++;
                                continue;
                            }

                            variables.push(await this.cdpValueToVariable(displayName, val));
                            localIndex++;
                        }

                        // Append DWARF linear-memory variables.
                        const wasmOffset = (frame.location?.columnNumber as number) || 0;
                        await this.appendMemoryVariables(
                            variables, frame.callFrameId, wasmOffset, wasmLocalValues,
                        );
                    } catch { /* ignore */ }
                }
            }
        }

        this.sendDapResponse(msg, true, undefined, { variables });
    }

    /**
     * Reads DWARF linear-memory variables for the current frame and appends them to `variables`.
     *
     * For each memory variable returned by `sourceLocationResolver.memoryVariablesAt`:
     * 1. Compute the linear-memory address (frame-relative or absolute).
     * 2. Read `byteSize` bytes from `memories[0]` using `Debugger.evaluateOnCallFrame`.
     *    V8 exposes wasm linear memory as `memories[0]` in the wasm frame evaluate context.
     *    We build a hex string to avoid binary encoding issues.
     * 3. Decode the bytes as a display value using the `typeSummary` from the resolver.
     *
     * Failures are silently ignored (logged once per frame).
     */
    private async appendMemoryVariables(
        variables: any[],
        callFrameId: string | undefined,
        wasmOffset: number,
        wasmLocalValues: Map<number, number>,
    ): Promise<void> {
        if (!this.sourceLocationResolver || !callFrameId) return;
        const memVars = this.sourceLocationResolver.memoryVariablesAt(wasmOffset);
        if (!memVars || memVars.length === 0) return;

        // A DWARF fbreg param is often also mapped onto a wasm local by the
        // positional name fallback; the wasmvalue row already shows it.
        const alreadyShown = new Set(variables.map((entry) => entry?.name));

        let loggedReadFailure = false;
        for (const v of Array.from(memVars as ArrayLike<any>)) {
            if (alreadyShown.has(v.name)) continue;
            try {
                let address: number;
                if (!v.isFbreg) {
                    address = v.absoluteAddress;
                } else {
                    const fbLocalIdx = v.frameBaseLocalIndex as number;
                    if (fbLocalIdx < 0) continue; // wasm-global frame base, not yet supported
                    const frameBase = wasmLocalValues.get(fbLocalIdx);
                    if (frameBase === undefined) continue;
                    address = frameBase + (v.fbregOffset as number);
                }

                const byteSize = v.byteSize as number;
                if (byteSize <= 0 || byteSize > 4096) continue;

                const hexExpr = `(function(){var mem=memories[0].buffer;var arr=new Uint8Array(mem,${address},${byteSize});var h='';for(var i=0;i<arr.length;i++){var b=arr[i].toString(16);h+=(b.length<2?'0'+b:b);}return h;})()`;

                let hexStr: string | undefined;
                try {
                    const evalResult = await this.cdpSend("Debugger.evaluateOnCallFrame", {
                        callFrameId,
                        expression: hexExpr,
                        returnByValue: true,
                    });
                    hexStr = evalResult?.result?.value as string | undefined;
                } catch (e) {
                    if (!loggedReadFailure) {
                        console.warn(`[hexana] CDP linear-memory read failed for '${v.name}': ${e}`);
                        loggedReadFailure = true;
                    }
                    continue;
                }

                if (!hexStr || hexStr.length !== byteSize * 2) continue;

                // Decode hex string to a display value.
                // Use typeSummary as type and interpret the bytes as a little-endian integer for scalars.
                const displayValue = this.decodeHexMemory(hexStr, byteSize, v.typeSummary as string);
                variables.push({
                    name: v.name as string,
                    value: displayValue,
                    type: v.typeSummary as string,
                    variablesReference: 0,
                });
            } catch { /* skip this variable */ }
        }
    }

    /**
     * Decodes a little-endian hex string from wasm linear memory into a display string.
     * Handles scalars and falls back to hex for composite/unknown types.
     */
    private decodeHexMemory(hex: string, byteSize: number, typeSummary: string): string {
        if (!hex || hex.length !== byteSize * 2) return `0x${hex}`;
        // For small scalars, decode as little-endian integer and show the value.
        // Composite types (struct/array) show as hex since byte-level decode is in Kotlin.
        if (byteSize <= 8 && !typeSummary.startsWith("[") && typeSummary !== "struct") {
            let value = 0;
            for (let i = byteSize - 1; i >= 0; i--) {
                value = (value * 256 + parseInt(hex.substring(i * 2, i * 2 + 2), 16));
            }
            return String(value);
        }
        return `0x${hex}`;
    }

    private buildWasmLocalToNameMap(frame: any): Map<number, string> {
        const names = this.sourceLocationResolver?.wasmLocalNames(
            (frame.functionName as string) || null,
        ) || [];
        const result = new Map<number, string>();
        for (const entry of Array.from(names as Array<{ index: number; name: string }>)) {
            const index = Number(entry.index);
            const name = String(entry.name || "");
            if (Number.isFinite(index) && name) {
                result.set(index, name);
            }
        }
        return result;
    }

    private resolveVariableDisplayName(name: string, localToName: Map<number, string>, positionalIndex: number): string {
        if (localToName.size === 0) return name;

        const match = WASM_VAR_REGEX.exec(name);
        if (match) {
            const index = Number(match[1]);
            return localToName.get(index) || name;
        }

        return localToName.get(positionalIndex) || name;
    }

    private async cdpValueToVariable(name: string, val: any): Promise<any> {
        const type = val.type || "unknown";
        const subtype = val.subtype;
        let value: string;
        let variablesReference = 0;

        if (subtype === "wasmvalue") {
            value = await this.resolveWasmValue(val) || val.description || "wasmvalue";
        } else if (val.value !== undefined) {
            value = String(val.value);
        } else if (val.description) {
            value = val.description;
        } else {
            value = type;
        }

        if (subtype !== "wasmvalue" && val.objectId && (type === "object" || type === "function")) {
            variablesReference = this.storeObjectRef(val.objectId);
        }

        return { name, value, type: subtype === "wasmvalue" ? (val.description || subtype) : (subtype || type), variablesReference };
    }

    private async resolveWasmValue(val: any): Promise<string | undefined> {
        const objectId = val.objectId;
        if (!objectId) return undefined;

        try {
            const result = await this.cdpSend("Runtime.getProperties", {
                objectId,
                ownProperties: true,
            });
            for (const prop of (result.result || [])) {
                if (prop.name !== "value") continue;
                const innerValue = prop.value || {};
                const value = innerValue.value;
                if (value === undefined || value === null) return undefined;
                if (typeof value === "number" && Number.isInteger(value)) {
                    return String(value);
                }
                return String(value);
            }
        } catch {
            // Fall back to the CDP description below.
        }
        return undefined;
    }

    // ---- DAP: evaluate ----

    private async handleEvaluate(msg: any): Promise<void> {
        const expression = msg.arguments?.expression || "";
        const frameId = msg.arguments?.frameId;
        const frame = frameId !== undefined ? this.cdpFrameForDapId(frameId) : undefined;

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

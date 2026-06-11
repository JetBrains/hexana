import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import * as vscode from "vscode";
import { execFileSync, spawn, ChildProcess } from "child_process";
import { extensionLib } from "./WasmRunConfigurations";
import { getDebugOutputChannel, readWasmBytes } from "./WasmDebugAdapter";

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

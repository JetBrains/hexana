import { execFile } from "child_process";
import * as fs from "fs";
import * as http from "http";
import * as path from "path";
import * as vscode from "vscode";
import type { JsWasmAttachTarget } from "./extension-lib";
import { extensionLib } from "./WasmRunConfigurations";

/**
 * Attaching `wasm` debug sessions to processes the extension did not start:
 * `wasmtime run -g host:port` / `iwasm -g=host:port` gdbstubs, Node.js
 * started with `--inspect*`, and Chromium started with
 * `--remote-debugging-port`.
 *
 * Which processes qualify is decided by the shared Kotlin detector
 * (`detectAttachTarget`, also behind IntelliJ's Attach to Process dialog);
 * this file only lists processes, shows the picker and resolves endpoints.
 */

/** Command bound to `${command:hexana.pickWasmProcess}` in attach configurations. */
export const PICK_WASM_PROCESS_COMMAND = "hexana.pickWasmProcess";

export interface ListedProcess {
    readonly pid: number;
    readonly commandLine: string;
}

export interface DetectedProcess extends ListedProcess {
    readonly target: JsWasmAttachTarget;
}

/** Lists local processes with their full command lines. */
export function listProcesses(): Promise<ListedProcess[]> {
    const windows = process.platform === "win32";
    const [cmd, args] = windows
        ? [
            "powershell.exe",
            [
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Get-CimInstance Win32_Process | ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }",
            ],
        ]
        : ["ps", ["-axww", "-o", "pid=,args="]];
    return new Promise((resolve, reject) => {
        execFile(cmd, args, { maxBuffer: 64 * 1024 * 1024, windowsHide: true }, (err, stdout) => {
            if (err) {
                reject(err);
                return;
            }
            resolve(parseProcessList(stdout, windows ? "\t" : " "));
        });
    });
}

/**
 * Parses `pid<separator>command line` rows (exported for unit tests).
 * Rows without a command line (kernel threads, access-denied entries) are skipped.
 */
export function parseProcessList(output: string, separator: string): ListedProcess[] {
    const result: ListedProcess[] = [];
    for (const rawLine of output.split(/\r?\n/)) {
        const line = rawLine.trim();
        const split = line.indexOf(separator);
        if (split <= 0) continue;
        const pid = Number(line.substring(0, split).trim());
        const commandLine = line.substring(split + 1).trim();
        if (!Number.isInteger(pid) || pid <= 0 || !commandLine) continue;
        result.push({ pid, commandLine });
    }
    return result;
}

/** Local processes Hexana can attach a `wasm` debug session to. */
export async function listAttachableProcesses(): Promise<DetectedProcess[]> {
    const lib = extensionLib;
    if (!lib) return [];
    const processes = await listProcesses();
    const busy = gdbStubEndpointsInUse();
    const result: DetectedProcess[] = [];
    for (const p of processes) {
        if (p.pid === process.pid) continue;
        const target = lib.detectAttachTarget(p.commandLine);
        if (!target) continue;
        // A gdbstub serves one debugger at a time.
        if (isGdbStub(target) && busy.has(`${target.host}:${target.port}`)) continue;
        result.push({ ...p, target });
    }
    return result;
}

function isGdbStub(target: JsWasmAttachTarget): boolean {
    return target.runtime === "wasmtime" || target.runtime === "wamr";
}

/** `host:port` of every gdbstub an active `wasm` session (launched or attached) is connected to. */
function gdbStubEndpointsInUse(): Set<string> {
    const endpoints = new Set<string>();
    for (const s of activeWasmSessions) {
        const c = s.configuration;
        if (c._wasmtimeGdbPort) endpoints.add(`${c._wasmtimeGdbHost || "127.0.0.1"}:${c._wasmtimeGdbPort}`);
        if (c._wamrDebugPort) endpoints.add(`${c._wamrDebugHost || "127.0.0.1"}:${c._wamrDebugPort}`);
        if (c.request === "attach" && (c.runtime === "wasmtime" || c.runtime === "wamr") && c.port) {
            endpoints.add(`${c.host || "127.0.0.1"}:${c.port}`);
        }
    }
    return endpoints;
}

/** `wasm` sessions currently running; VS Code exposes only the focused one directly. */
const activeWasmSessions = new Set<vscode.DebugSession>();

function describeTarget(target: JsWasmAttachTarget): string {
    switch (target.runtime) {
        case "wasmtime": return `Wasmtime gdbstub ${target.host}:${target.port}`;
        case "wamr": return `WAMR gdbstub ${target.host}:${target.port}`;
        case "nodejs": return `Node.js inspector ${target.host}:${target.port}`;
        case "browser": return target.port ? `Chrome DevTools port ${target.port}` : "Chrome DevTools";
    }
}

/** Shows a QuickPick of attachable processes and returns the chosen pid as a string. */
export async function pickWasmProcess(): Promise<string | undefined> {
    let processes: DetectedProcess[];
    try {
        processes = await listAttachableProcesses();
    } catch (e) {
        vscode.window.showErrorMessage(`Cannot list processes: ${e instanceof Error ? e.message : String(e)}`);
        return undefined;
    }
    if (processes.length === 0) {
        vscode.window.showInformationMessage(
            "No attachable WebAssembly process found. Start wasmtime with `-g <host:port>`, " +
            "iwasm with `-g=<host:port>`, Node.js with `--inspect`, or Chrome with " +
            "`--remote-debugging-port`.",
        );
        return undefined;
    }
    const picked = await vscode.window.showQuickPick(
        processes.map((p) => ({
            label: `${path.basename(p.commandLine.split(/\s+/)[0] ?? "")} (${p.pid})`,
            description: describeTarget(p.target) + (p.target.wasmPath ? ` — ${p.target.wasmPath}` : ""),
            detail: p.commandLine,
            pid: p.pid,
        })),
        { placeHolder: "Select a WebAssembly process to attach to", matchOnDetail: true },
    );
    return picked ? String(picked.pid) : undefined;
}

/**
 * Completes an `attach` configuration after variable substitution:
 * fills `runtime`/`host`/`port`/`wasmFile` from a `processId`, and resolves
 * `wsUrl` for CDP runtimes. Shows an error and returns undefined when the
 * target cannot be reached.
 */
export async function resolveAttachConfiguration(
    folder: vscode.WorkspaceFolder | undefined,
    config: vscode.DebugConfiguration,
): Promise<vscode.DebugConfiguration | undefined> {
    try {
        if (config.processId !== undefined && config.processId !== "") {
            const pid = Number(config.processId);
            const listed = (await listProcesses()).find((p) => p.pid === pid);
            const target = listed ? extensionLib?.detectAttachTarget(listed.commandLine) : null;
            if (!target) {
                throw new Error(`process ${config.processId} serves no WebAssembly debug endpoint`);
            }
            config.runtime = target.runtime;
            if (target.host && !config.host) config.host = target.host;
            if (!config.port) config.port = target.port;
            if (target.userDataDir && !config.userDataDir) config.userDataDir = target.userDataDir;
            if (!config.wasmFile && target.wasmPath) {
                config.wasmFile = resolveModulePath(target.wasmPath, pid, folder);
            }
        }

        const runtime = config.runtime as string | undefined;
        if (!runtime) {
            throw new Error("set `runtime` and `port`, or pick a process with `processId`");
        }
        if (config.wasmFile && !fs.existsSync(config.wasmFile)) {
            // A wrong optional path must not block the attach: LLDB reads
            // DWARF from the stub and CDP from the target's own module.
            config.wasmFile = undefined;
        }
        if (runtime === "wasmtime" || runtime === "wamr") {
            if (!config.port) throw new Error("`port` of the gdbstub is required");
            config.host = config.host || "127.0.0.1";
        } else if (runtime === "nodejs" || runtime === "browser") {
            if (!config.wsUrl) config.wsUrl = await resolveCdpAttachWebSocketUrl(config);
        } else {
            throw new Error(`attach is not supported for runtime '${runtime}'`);
        }
        return config;
    } catch (e) {
        vscode.window.showErrorMessage(`Cannot attach: ${e instanceof Error ? e.message : String(e)}`);
        return undefined;
    }
}

/**
 * Resolves a module path from a process's command line. Relative paths are
 * tried against the process's working directory (Linux only), then the
 * workspace folder.
 */
export function resolveModulePath(
    wasmPath: string,
    pid: number,
    folder: vscode.WorkspaceFolder | undefined,
): string | undefined {
    if (path.isAbsolute(wasmPath)) return fs.existsSync(wasmPath) ? wasmPath : undefined;
    const bases: string[] = [];
    if (process.platform === "linux") {
        try {
            bases.push(fs.readlinkSync(`/proc/${pid}/cwd`));
        } catch {
            // not readable
        }
    }
    if (folder) bases.push(folder.uri.fsPath);
    for (const base of bases) {
        const candidate = path.resolve(base, wasmPath);
        if (fs.existsSync(candidate)) return candidate;
    }
    return undefined;
}

/**
 * Finds the WebSocket debugger URL of a Node.js inspector (`/json/list`) or
 * of the first page of a browser (`/json`). Browsers started with
 * `--remote-debugging-port=0` are reached through `DevToolsActivePort`.
 */
export async function resolveCdpAttachWebSocketUrl(config: vscode.DebugConfiguration): Promise<string> {
    if (config.runtime === "nodejs") {
        const host = (config.host as string) || "127.0.0.1";
        const port = Number(config.port) || 9229;
        const url = await findWebSocketDebuggerUrl(host, port, "/json/list", () => true);
        if (!url) {
            throw new Error(`no Node.js inspector answered at ${host}:${port}; another debugger may be attached`);
        }
        return url;
    }
    let port = Number(config.port) || 0;
    if (port === 0 && config.userDataDir) {
        port = readDevToolsActivePort(config.userDataDir as string) ?? 0;
    }
    if (port === 0) {
        throw new Error("the browser's DevTools port is unknown (set `port`, or `userDataDir` for --remote-debugging-port=0)");
    }
    const url = await findWebSocketDebuggerUrl("127.0.0.1", port, "/json", (t) => t.type === "page");
    if (!url) throw new Error(`the browser on DevTools port ${port} has no page to debug`);
    return url;
}

/** Reads the port from `<userDataDir>/DevToolsActivePort` (its first line). */
export function readDevToolsActivePort(userDataDir: string): number | undefined {
    try {
        const first = fs.readFileSync(path.join(userDataDir, "DevToolsActivePort"), "utf-8").split(/\r?\n/)[0];
        const port = Number(first.trim());
        return Number.isInteger(port) && port > 0 && port <= 65535 ? port : undefined;
    } catch {
        return undefined;
    }
}

function findWebSocketDebuggerUrl(
    host: string,
    port: number,
    endpoint: string,
    accept: (target: any) => boolean,
): Promise<string | undefined> {
    const hostPart = host.includes(":") ? `[${host}]` : host;
    return new Promise((resolve) => {
        const req = http.get(`http://${hostPart}:${port}${endpoint}`, { timeout: 2000 }, (res) => {
            let data = "";
            res.setEncoding("utf-8");
            res.on("data", (chunk: string) => data += chunk);
            res.on("end", () => {
                try {
                    const targets = JSON.parse(data) as any[];
                    const match = targets.find((t) => accept(t) && t.webSocketDebuggerUrl);
                    resolve(match?.webSocketDebuggerUrl);
                } catch {
                    resolve(undefined);
                }
            });
        });
        req.on("timeout", () => req.destroy());
        req.on("error", () => resolve(undefined));
    });
}

/** Registers the process picker and tracks running `wasm` sessions. */
export function registerWasmAttach(context: vscode.ExtensionContext, debugType: string): void {
    context.subscriptions.push(
        vscode.commands.registerCommand(PICK_WASM_PROCESS_COMMAND, pickWasmProcess),
        vscode.debug.onDidStartDebugSession((s) => {
            if (s.type === debugType) activeWasmSessions.add(s);
        }),
        vscode.debug.onDidTerminateDebugSession((s) => activeWasmSessions.delete(s)),
    );
}

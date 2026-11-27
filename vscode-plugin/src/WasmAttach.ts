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
    /** Parent pid; 0 when unknown. */
    readonly ppid: number;
    readonly commandLine: string;
    /** Full executable path when the listing provides it separately (macOS, Windows). */
    readonly executable?: string;
}

export interface DetectedProcess extends ListedProcess {
    readonly target: JsWasmAttachTarget;
}

const WINDOWS = process.platform === "win32";

/** Lists local processes with their command lines. */
export async function listProcesses(): Promise<ListedProcess[]> {
    if (WINDOWS) {
        const out = await run("powershell.exe", [
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "Get-CimInstance Win32_Process | ForEach-Object " +
            "{ \"$($_.ProcessId)`t$($_.ParentProcessId)`t$($_.ExecutablePath)`t$($_.CommandLine)\" }",
        ]);
        return parseWindowsProcessList(out);
    }
    const listed = parseUnixProcessList(await run("ps", ["-axww", "-o", "pid=,ppid=,args="]));
    if (process.platform !== "darwin") return listed;
    // macOS `ps -o args` joins argv with spaces, so an executable path with
    // spaces is ambiguous there; `comm` holds it alone.
    const executables = parseUnixCommList(await run("ps", ["-axww", "-o", "pid=,comm="]).catch(() => ""));
    return listed.map((p) => ({ ...p, executable: executables.get(p.pid) }));
}

function run(cmd: string, args: string[]): Promise<string> {
    return new Promise((resolve, reject) => {
        execFile(cmd, args, { maxBuffer: 64 * 1024 * 1024, windowsHide: true, timeout: 10_000 }, (err, stdout) => {
            // lsof exits 1 when nothing matches; its (empty) output is still the answer.
            if (err && !stdout) {
                reject(err);
                return;
            }
            resolve(stdout);
        });
    });
}

/**
 * Parses `ps -o pid=,ppid=,args=` rows (exported for unit tests). Rows
 * without a command line (kernel threads) are skipped.
 */
export function parseUnixProcessList(output: string): ListedProcess[] {
    const result: ListedProcess[] = [];
    for (const line of output.split(/\r?\n/)) {
        const match = /^\s*(\d+)\s+(\d+)\s+(.+?)\s*$/.exec(line);
        if (!match) continue;
        result.push({ pid: Number(match[1]), ppid: Number(match[2]), commandLine: match[3] });
    }
    return result;
}

/** Parses `ps -o pid=,comm=` rows into pid → executable path (exported for unit tests). */
export function parseUnixCommList(output: string): Map<number, string> {
    const result = new Map<number, string>();
    for (const line of output.split(/\r?\n/)) {
        const match = /^\s*(\d+)\s+(.+?)\s*$/.exec(line);
        if (match) result.set(Number(match[1]), match[2]);
    }
    return result;
}

/**
 * Parses `pid<TAB>ppid<TAB>executable<TAB>command line` rows from
 * `Win32_Process` (exported for unit tests). Rows without a command line
 * (system or access-denied processes) are skipped.
 */
export function parseWindowsProcessList(output: string): ListedProcess[] {
    const result: ListedProcess[] = [];
    for (const line of output.split(/\r?\n/)) {
        const fields = line.split("\t");
        if (fields.length < 4) continue;
        const pid = Number(fields[0].trim());
        const commandLine = fields.slice(3).join("\t").trim();
        if (!Number.isInteger(pid) || pid <= 0 || !commandLine) continue;
        result.push({
            pid,
            ppid: Number(fields[1].trim()) || 0,
            commandLine,
            executable: fields[2].trim() || undefined,
        });
    }
    return result;
}

/**
 * Runs the shared detector on a listed process. Linux exposes the exact
 * argv in `/proc/<pid>/cmdline`, which is used instead of the joined line.
 */
export function detectProcess(p: ListedProcess): JsWasmAttachTarget | null {
    const lib = extensionLib;
    if (!lib) return null;
    if (process.platform === "linux") {
        const argv = readProcCmdline(p.pid);
        if (argv && argv.length > 0) return lib.detectAttachTargetFromArgs(argv[0], argv.slice(1));
    }
    return lib.detectAttachTarget(p.commandLine, WINDOWS, p.executable ?? null);
}

function readProcCmdline(pid: number): string[] | undefined {
    try {
        const raw = fs.readFileSync(`/proc/${pid}/cmdline`, "utf-8");
        const argv = raw.split("\0");
        if (argv.length > 0 && argv[argv.length - 1] === "") argv.pop();
        return argv;
    } catch {
        return undefined;
    }
}

/** Local processes Hexana can attach a `wasm` debug session to. */
export async function listAttachableProcesses(): Promise<DetectedProcess[]> {
    if (!extensionLib) return [];
    const processes = await listProcesses();
    const busy = gdbStubEndpointsInUse();
    const result: DetectedProcess[] = [];
    for (const p of processes) {
        if (p.pid === process.pid) continue;
        // Cheap pre-filter before the per-process detection (and, on Linux, a file read).
        if (!/wasmtime|iwasm|node|inspect|remote-debugging-port/i.test(p.commandLine)) continue;
        const target = detectProcess(p);
        if (!target) continue;
        // A gdbstub serves one debugger at a time.
        if (isGdbStub(target) && !target.needsPortDiscovery && busy.has(`${target.host}:${target.port}`)) continue;
        // The extension's own debug launches (`node --inspect-brk=0`, Chrome
        // with `--remote-debugging-port=0`) are its children and already
        // have a session.
        if (target.needsPortDiscovery && p.ppid === process.pid) continue;
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
    const where = target.needsPortDiscovery
        ? "port chosen at startup"
        : target.host ? `${target.host}:${target.port}` : `port ${target.port}`;
    switch (target.runtime) {
        case "wasmtime": return `Wasmtime gdbstub ${where}`;
        case "wamr": return `WAMR gdbstub ${where}`;
        case "nodejs": return `Node.js inspector ${where}`;
        case "browser": return `Chrome DevTools ${where}`;
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
            label: `${path.basename(p.executable ?? p.commandLine.split(/\s+/)[0] ?? "")} (${p.pid})`,
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
            const target = listed ? detectProcess(listed) : null;
            if (!target) {
                throw new Error(`process ${config.processId} serves no WebAssembly debug endpoint`);
            }
            config.runtime = target.runtime;
            if (target.host && !config.host) config.host = target.host;
            if (!config.port) config.port = target.port;
            if (target.userDataDir && !config.userDataDir) config.userDataDir = target.userDataDir;
            if (!config.wasmFile) {
                config.wasmFile = resolveModulePath(target.wasmPathCandidates, pid, folder);
            }
            if (target.needsPortDiscovery && !config.wsUrl) {
                await discoverEndpoint(config, pid);
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
 * Resolves the module from its path candidates (longest first; a listing
 * that dropped quoting splits paths with spaces). The first that exists
 * wins. Relative paths are tried against the process's working directory
 * (Linux only), then the workspace folder.
 */
export function resolveModulePath(
    candidates: readonly string[],
    pid: number,
    folder: vscode.WorkspaceFolder | undefined,
): string | undefined {
    let bases: string[] | undefined;
    for (const wasmPath of candidates) {
        if (!wasmPath) continue;
        if (path.isAbsolute(wasmPath)) {
            if (fs.existsSync(wasmPath)) return wasmPath;
            continue;
        }
        if (!bases) {
            bases = [];
            if (process.platform === "linux") {
                try {
                    bases.push(fs.readlinkSync(`/proc/${pid}/cwd`));
                } catch {
                    // not readable
                }
            }
            if (folder) bases.push(folder.uri.fsPath);
        }
        for (const base of bases) {
            const candidate = path.resolve(base, wasmPath);
            if (fs.existsSync(candidate)) return candidate;
        }
    }
    return undefined;
}

/**
 * Fills in the endpoint of a target on an OS-chosen port from the ports the
 * process listens on. A gdbstub takes a single client, so it is not probed:
 * the runtime must listen on exactly one port. CDP endpoints are probed
 * over HTTP, which is harmless. A browser's `DevToolsActivePort` is tried first.
 */
export async function discoverEndpoint(config: vscode.DebugConfiguration, pid: number): Promise<void> {
    const runtime = config.runtime as string;
    if (runtime === "browser" && config.userDataDir) {
        const port = readDevToolsActivePort(config.userDataDir as string);
        if (port) {
            config.port = port;
            return;
        }
    }
    const endpoints = await listeningEndpoints(pid);
    if (runtime === "wasmtime" || runtime === "wamr") {
        if (endpoints.length !== 1) {
            throw new Error(endpoints.length === 0
                ? "the runtime is not listening on any TCP port yet"
                : `the runtime listens on several ports (${endpoints.map((e) => `${e.host}:${e.port}`).join(", ")}); ` +
                  "start it with an explicit gdbstub port");
        }
        config.host = endpoints[0].host;
        config.port = endpoints[0].port;
        return;
    }
    for (const e of endpoints) {
        const url = runtime === "nodejs"
            ? await findWebSocketDebuggerUrl(e.host, e.port, "/json/list", () => true)
            : await findWebSocketDebuggerUrl(e.host, e.port, "/json", (t) => t.type === "page");
        if (url) {
            config.host = e.host;
            config.port = e.port;
            config.wsUrl = url;
            return;
        }
    }
    throw new Error(runtime === "nodejs"
        ? "no Node.js inspector answered on the ports this process listens on; another debugger may be attached"
        : "cannot find the DevTools port of this browser");
}

/**
 * The TCP endpoints process `pid` listens on: `/proc` on Linux (falling back
 * to lsof), `lsof` on macOS, `netstat` on Windows. Parsing is shared Kotlin
 * (`ListeningPorts`), the same as the IntelliJ side.
 */
export async function listeningEndpoints(pid: number): Promise<Array<{ host: string; port: number }>> {
    const lib = extensionLib;
    if (!lib) return [];
    if (WINDOWS) {
        return lib.parseNetstatListening(await run("netstat", lib.netstatListeningArguments()), pid);
    }
    if (process.platform === "linux") {
        const fromProc = listeningEndpointsFromProc(pid);
        if (fromProc.length > 0) return fromProc;
    }
    const lsof = process.platform === "darwin" && fs.existsSync("/usr/sbin/lsof") ? "/usr/sbin/lsof" : "lsof";
    return lib.parseLsofListening(await run(lsof, lib.lsofListeningArguments(pid)).catch(() => ""));
}

function listeningEndpointsFromProc(pid: number): Array<{ host: string; port: number }> {
    const lib = extensionLib!;
    const inodes: string[] = [];
    try {
        for (const fd of fs.readdirSync(`/proc/${pid}/fd`)) {
            try {
                const inode = lib.procSocketInode(fs.readlinkSync(`/proc/${pid}/fd/${fd}`));
                if (inode) inodes.push(inode);
            } catch {
                // closed meanwhile
            }
        }
    } catch {
        return [];
    }
    if (inodes.length === 0) return [];
    const result: Array<{ host: string; port: number }> = [];
    for (const table of ["/proc/net/tcp", "/proc/net/tcp6"]) {
        try {
            result.push(...lib.parseProcNetTcpListening(fs.readFileSync(table, "utf-8"), inodes));
        } catch {
            // table absent (no IPv6)
        }
    }
    return result;
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

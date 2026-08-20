import * as fs from "fs";
import * as vscode from "vscode";
import { extensionLib } from "./WasmRunConfigurations";
import type { SourceLocationResolver, WasmModuleIndex } from "./extension-lib";

/**
 * The "Wasm" debug view: a webview in the bottom panel (next to the Debug
 * Console) mirroring the IntelliJ Wasm debugger tab — the disassembly of the
 * paused function on the left, the operand stack on the right.
 *
 * While the view is visible, stepping switches to instruction granularity
 * (the adapter's "hexanaInstructionMode" custom request), matching the
 * IntelliJ tab-selected semantics; content refreshes on every stop via the
 * "hexanaWasmState" custom request.
 */
class WasmDebugViewProvider implements vscode.WebviewViewProvider {

    static readonly VIEW_ID = "hexanaWasmView";

    private view: vscode.WebviewView | undefined;
    private readonly visibilityEmitter = new vscode.EventEmitter<boolean>();
    /** Fires with the view's visibility whenever it changes. */
    readonly onDidChangeVisibility = this.visibilityEmitter.event;

    isVisible(): boolean {
        return this.view?.visible ?? false;
    }

    resolveWebviewView(view: vscode.WebviewView): void {
        this.view = view;
        view.webview.options = { enableScripts: true };
        view.webview.html = buildHtml();
        view.onDidChangeVisibility(() => this.visibilityEmitter.fire(view.visible));
        this.visibilityEmitter.fire(view.visible);
    }

    update(state: unknown): void {
        this.view?.webview.postMessage({ type: "update", state });
    }

    clear(): void {
        this.view?.webview.postMessage({ type: "clear" });
    }
}

/** Debug configuration runtimes served by the CDP adapter (V8 exposes the operand stack there). */
function isCdpRuntime(session: vscode.DebugSession): boolean {
    const runtime = session.configuration.runtime as string | undefined;
    return runtime === "nodejs" || runtime === "browser";
}

/** LLDB-based runtimes: real wasm disassembly, no operand stack. */
function isLldbRuntime(session: vscode.DebugSession): boolean {
    const runtime = session.configuration.runtime as string | undefined;
    return runtime === "wasmtime" || runtime === "wamr";
}

/**
 * LLDB sessions debugging the wasm GUEST through the LLDB wasm process
 * plugin (wasmtime gdbstub, WAMR stub). There the reported PCs carry the
 * wasm byte offset in the low 32 bits, enabling exact instruction
 * highlight and per-instruction stepping. False for the legacy native
 * wasmtime path, where PCs are native JIT addresses.
 */
function isGuestLldbSession(session: vscode.DebugSession): boolean {
    const runtime = session.configuration.runtime as string | undefined;
    return runtime === "wamr" ||
        (runtime === "wasmtime" && !!session.configuration._wasmtimeGdbPort);
}

function activeWasmSession(): vscode.DebugSession | undefined {
    const session = vscode.debug.activeDebugSession;
    return session && session.type === "wasm" && (isCdpRuntime(session) || isLldbRuntime(session))
        ? session
        : undefined;
}

/**
 * Extension-host disassembly context for an LLDB session (wasmtime/wamr):
 * lldb-dap only reports (file, line), so the extension maps that to a wasm
 * offset via the DWARF line table and renders the module's disassembly
 * itself. Both indexes are built lazily from the session's wasm file.
 *
 * `modules` is per core module, because that is the granularity the stub
 * addresses: a component-model binary embeds one core module per `module`
 * section and its offsets belong to the embedded module's own space.
 */
interface LldbViewContext {
    modules: WasmModuleIndex | null;
    resolver: SourceLocationResolver | null;
}

const lldbContexts = new Map<string, LldbViewContext>();

function lldbContextFor(session: vscode.DebugSession): LldbViewContext {
    let ctx = lldbContexts.get(session.id);
    if (!ctx) {
        ctx = { modules: null, resolver: null };
        const wasmFile = session.configuration.wasmFile as string | undefined;
        if (extensionLib && wasmFile && fs.existsSync(wasmFile)) {
            try {
                const buf = fs.readFileSync(wasmFile);
                const int8 = new Int8Array(buf.buffer, buf.byteOffset, buf.byteLength);
                ctx.modules = extensionLib.createWasmModuleIndex(int8);
                ctx.resolver = extensionLib.SourceLocationResolver.create(int8);
            } catch {
                // Leave nulls — the view will show its empty state.
            }
        }
        lldbContexts.set(session.id, ctx);
    }
    return ctx;
}

/**
 * Sends a raw GDB-remote packet through lldb-dap's console escape (an
 * `evaluate` expression starting with a backtick runs an LLDB console
 * command) and returns the hex payload from the "response:" line, or null
 * on empty/absent response.
 */
async function sendRawPacket(session: vscode.DebugSession, packet: string): Promise<string | null> {
    try {
        const res = await session.customRequest("evaluate", {
            expression: "`process plugin packet send " + packet,
            context: "repl",
        });
        const lines = String(res?.result ?? "").split(/\r?\n/);
        return extensionLib?.parseRawPacketOutput(lines) ?? null;
    } catch {
        return null;
    }
}

/** One operand-stack row sent to the webview. */
interface StackEntry { index: number; type: string; value: string; }

/**
 * Builds the operand-stack entries for an LLDB pause: the statically
 * reconstructed shape (types + provenance) at [pauseOffset] of core module
 * [moduleId], enriched with concrete values from the gdbstub for guest sessions.
 * Mirrors IntelliJ's `WasmDebugProcess.updateWasmDisassemblyTab` enrichment
 * rules:
 *
 * - wasmtime gdbstub: `qWasmStackValue:<frame>;<index>` per slot (cap 64);
 *   the first empty response means the stub does not support it — remaining
 *   slots keep their symbolic provenance.
 * - WAMR: its stub leaves `qWasmStackValue` UNANSWERED, desyncing the
 *   connection (verified against WAMR 2.4.5) — never send it. Only slots
 *   provably mirroring a local/global are resolved via `qWasmLocal`/
 *   `qWasmGlobal`, cached per packet.
 */
async function lldbStackEntries(
    session: vscode.DebugSession,
    ctx: LldbViewContext,
    moduleId: number,
    pauseOffset: number,
    guest: boolean,
): Promise<{ entries: StackEntry[]; stackNote?: string }> {
    const shape = ctx.modules?.stackShapeFor(moduleId, pauseOffset)?.stackAt(pauseOffset);
    if (!shape) {
        return { entries: [] };
    }
    if (!shape.slots) {
        return { entries: [], stackNote: `Stack shape unavailable: ${shape.unavailableReason ?? "?"}` };
    }
    const slots = shape.slots;
    const entries: StackEntry[] = slots.map((slot, i) => ({ index: i, type: slot.type, value: slot.provenance }));
    if (!guest) {
        return { entries }; // legacy native path: symbolic shape only
    }

    const decorate = (i: number, hex: string) => {
        const decoded = extensionLib?.decodeWasmStackValueHex(hex, entries[i].type);
        if (decoded) {
            entries[i] = { ...entries[i], value: `${decoded} (${entries[i].value})` };
        }
    };

    const cap = Math.min(entries.length, 64);
    if (session.configuration.runtime === "wasmtime") {
        for (let i = 0; i < cap; i++) {
            const hex = await sendRawPacket(session, `qWasmStackValue:0;${i}`);
            if (hex === null) break; // out of range or unsupported stub
            decorate(i, hex);
        }
    } else {
        const cache = new Map<string, string | null>();
        let stubResponsive = true;
        for (let i = 0; i < cap && stubResponsive; i++) {
            const slot = slots[i];
            const packet = slot.localRef >= 0
                ? `qWasmLocal:0;${slot.localRef}`
                : slot.globalRef >= 0 ? `qWasmGlobal:0;${slot.globalRef}` : null;
            if (!packet) continue;
            let hex: string | null;
            if (cache.has(packet)) {
                hex = cache.get(packet) ?? null;
            } else {
                hex = await sendRawPacket(session, packet);
                cache.set(packet, hex);
            }
            if (hex === null) {
                stubResponsive = false;
                continue;
            }
            decorate(i, hex);
        }
    }
    return { entries };
}

/**
 * Registers the "Wasm" panel view, the "Hexana: Open Wasm View" command, and
 * a debug adapter tracker that keeps the view in sync with CDP wasm debug
 * sessions (nodejs/browser; compiled runtimes like wasmtime have no operand
 * stack and no per-instruction wasm offsets to show).
 */
export function registerWasmDebugView(context: vscode.ExtensionContext): void {
    const provider = new WasmDebugViewProvider();
    context.subscriptions.push(
        vscode.window.registerWebviewViewProvider(WasmDebugViewProvider.VIEW_ID, provider, {
            webviewOptions: { retainContextWhenHidden: true },
        }),
    );

    const focusView = () => vscode.commands.executeCommand(`${WasmDebugViewProvider.VIEW_ID}.focus`);
    context.subscriptions.push(
        vscode.commands.registerCommand("hexana.openWasmView", focusView),
        vscode.commands.registerCommand("hexana.openWasmDisassembly", focusView),
    );

    const setViewContext = (active: boolean) =>
        vscode.commands.executeCommand("setContext", "hexana.wasmDebugActive", active);

    const pushInstructionMode = (session: vscode.DebugSession | undefined, enabled: boolean) => {
        session?.customRequest("hexanaInstructionMode", { enabled }).then(undefined, () => { /* session gone */ });
    };

    /** CDP sessions: the adapter assembles the full state (disasm + operand stack). */
    const refreshCdp = (session: vscode.DebugSession) => {
        session.customRequest("hexanaWasmState").then(
            (state) => provider.update(state),
            () => provider.clear(),
        );
    };

    /**
     * LLDB sessions (wasmtime/wamr): resolve the paused (file, line) to a wasm
     * offset via the DWARF line table and render that function's disassembly.
     * Line granularity only — LLDB reports native PCs, not wasm offsets — and
     * no operand stack (Cranelift/AOT compiles the stack machine away).
     */
    const refreshLldb = async (session: vscode.DebugSession, threadId?: number) => {
        const ctx = lldbContextFor(session);
        if (!ctx.modules || !extensionLib) {
            provider.clear();
            return;
        }
        const lib = extensionLib;
        const modules = ctx.modules;
        try {
            let tid = threadId;
            if (tid === undefined) {
                const threads = await session.customRequest("threads");
                tid = threads?.threads?.[0]?.id;
            }
            if (tid === undefined) {
                provider.clear();
                return;
            }
            const guest = isGuestLldbSession(session);
            const st = await session.customRequest("stackTrace", { threadId: tid, startFrame: 0, levels: 5 });
            /**
             * Renders the stop at [pauseOffset] of core module [moduleId]. A negative
             * [moduleId] means the id is unknown and the module is deduced from the
             * offset — for a single-module binary that is always the right answer.
             */
            const render = async (moduleId: number, pauseOffset: number): Promise<boolean> => {
                const disasm = modules.disassemblyFor(moduleId, pauseOffset);
                if (!disasm) return false;
                const range = disasm.functionRangeForOffset(pauseOffset);
                if (!range) return false;
                const rows = disasm.rowsForFunction(range.index)
                    .map(r => ({ offset: r.offset, instruction: r.instruction }));
                const { entries, stackNote } = await lldbStackEntries(session, ctx, moduleId, pauseOffset, guest);
                provider.update({ rows, pauseOffset, entries, stackNote });
                return true;
            };
            for (const frame of (st?.stackFrames ?? [])) {
                // Guest sessions (gdbstub/WAMR): the frame PC carries both the core
                // module id and the byte offset inside it — exact instruction
                // highlight. The offset is module-relative, so for a component both
                // halves are needed to land in the right embedded module.
                const ipRef = frame?.instructionPointerReference as string | undefined;
                if (guest && ipRef) {
                    const offset = lib.wasmOffsetForGdbPcString(ipRef);
                    const moduleId = lib.wasmModuleIdForGdbPcString(ipRef);
                    // -1 means the reference was unparseable — fall through to line mapping.
                    if (offset >= 0 && await render(moduleId, offset)) return;
                }
                // Fallback: map the frame's (file, line) through the DWARF
                // line table. Line granularity only, and no module id — the
                // resolver reads the binary's DWARF, not a per-module index.
                if (!ctx.resolver) continue;
                const file = frame?.source?.path || frame?.source?.name;
                const line = frame?.line;
                if (!file || typeof line !== "number") continue;
                // The resolver takes 0-based lines; DAP lines are 1-based.
                const offsets: number[] = Array.from(ctx.resolver.sourceToAllWasmOffsets(file, line - 1));
                if (offsets.length === 0) continue;
                if (await render(-1, Math.min(...offsets))) return;
            }
            provider.clear();
        } catch {
            provider.clear();
        }
    };

    const refresh = (session: vscode.DebugSession) => {
        if (isCdpRuntime(session)) {
            refreshCdp(session);
        } else {
            void refreshLldb(session);
        }
    };

    // Visibility drives stepping granularity for CDP sessions, exactly like
    // the IntelliJ tab selection (selection, not keyboard focus — clicking
    // the step toolbar buttons moves focus away from the view). Becoming
    // visible also refreshes the content, so opening the view mid-pause
    // populates it immediately instead of waiting for the next stop.
    context.subscriptions.push(
        provider.onDidChangeVisibility(visible => {
            const session = activeWasmSession();
            if (session && isCdpRuntime(session)) {
                pushInstructionMode(session, visible);
            }
            if (visible && session) {
                refresh(session);
            }
        }),
    );

    context.subscriptions.push(
        vscode.debug.registerDebugAdapterTrackerFactory("wasm", {
            createDebugAdapterTracker(session: vscode.DebugSession) {
                const cdp = isCdpRuntime(session);
                if (!cdp && !isLldbRuntime(session)) {
                    return undefined;
                }
                setViewContext(true);
                return {
                    onDidSendMessage(message: any) {
                        if (message?.type !== "event") return;
                        if (message.event === "initialized") {
                            // A session may start while the view is already visible.
                            if (cdp) {
                                pushInstructionMode(session, provider.isVisible());
                            }
                        } else if (message.event === "stopped") {
                            if (cdp) {
                                refreshCdp(session);
                            } else {
                                void refreshLldb(session, message.body?.threadId);
                            }
                        } else if (message.event === "terminated") {
                            provider.clear();
                        }
                    },
                    onWillReceiveMessage(message: any) {
                        // Clear on resume; step updates arrive with the next stop.
                        if (message?.command === "continue") {
                            provider.clear();
                        }
                        // While the Wasm view is visible, guest LLDB sessions
                        // (wasmtime gdbstub / WAMR) step one wasm instruction:
                        // DAP granularity "instruction" makes lldb-dap issue
                        // stepi/nexti, and under the wasm plugin one native
                        // instruction IS one wasm instruction. CDP sessions
                        // handle this via hexanaInstructionMode instead.
                        if (
                            !cdp &&
                            (message?.command === "next" || message?.command === "stepIn" || message?.command === "stepOut") &&
                            provider.isVisible() &&
                            isGuestLldbSession(session)
                        ) {
                            message.arguments = { ...(message.arguments || {}), granularity: "instruction" };
                        }
                    },
                    onWillStopSession() {
                        provider.clear();
                        setViewContext(false);
                        lldbContexts.delete(session.id);
                    },
                };
            },
        }),
    );
}

/**
 * Static webview markup: disassembly table left, operand stack right.
 * Rendering happens in the inline script from "update" messages; the row
 * whose offset matches the pause offset exactly is highlighted, otherwise
 * the nearest offset at or below it (rows without a deduced offset show a
 * blank offset cell and are skipped for highlighting, like IntelliJ).
 */
function buildHtml(): string {
    return /* html */ `<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';">
<style>
    html, body { height: 100%; margin: 0; padding: 0; }
    body {
        display: flex;
        font-family: var(--vscode-editor-font-family, monospace);
        font-size: var(--vscode-editor-font-size, 13px);
        color: var(--vscode-foreground);
    }
    .pane { overflow: auto; height: 100%; box-sizing: border-box; }
    #disasm { flex: 2 1 0; border-right: 1px solid var(--vscode-panel-border, #444); }
    #stack { flex: 1 1 0; }
    table { border-collapse: collapse; width: 100%; }
    th {
        position: sticky; top: 0;
        text-align: left; padding: 2px 8px;
        background: var(--vscode-panel-background, var(--vscode-editor-background));
        color: var(--vscode-descriptionForeground);
        font-weight: normal;
    }
    td { padding: 1px 8px; white-space: pre; }
    .offset { color: var(--vscode-descriptionForeground); }
    tr.current {
        background: var(--vscode-list-activeSelectionBackground);
        color: var(--vscode-list-activeSelectionForeground);
    }
    .empty { padding: 8px; color: var(--vscode-descriptionForeground); }
</style>
</head>
<body>
<div id="disasm" class="pane"><div class="empty">Not paused in a wasm frame.</div></div>
<div id="stack" class="pane"><div class="empty">No operand stack.</div></div>
<script>
    const disasmPane = document.getElementById("disasm");
    const stackPane = document.getElementById("stack");

    function esc(s) {
        return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }

    function renderDisasm(rows, pauseOffset) {
        if (!rows || rows.length === 0) {
            disasmPane.innerHTML = '<div class="empty">Not paused in a wasm frame.</div>';
            return;
        }
        // Exact offset match wins; otherwise the nearest offset at or below
        // the pause offset. Rows with offset -1 are never highlighted.
        let current = -1;
        for (let i = 0; i < rows.length; i++) {
            const o = rows[i].offset;
            if (o < 0) continue;
            if (o === pauseOffset) { current = i; break; }
            if (o < pauseOffset) current = i;
            if (o > pauseOffset) break;
        }
        let html = '<table><tr><th>Offset</th><th>Instruction</th></tr>';
        for (let i = 0; i < rows.length; i++) {
            const r = rows[i];
            const offset = r.offset >= 0 ? "0x" + r.offset.toString(16) : "";
            html += '<tr' + (i === current ? ' class="current" id="current-row"' : "") + ">"
                + '<td class="offset">' + offset + "</td>"
                + "<td>" + esc(r.instruction) + "</td></tr>";
        }
        html += "</table>";
        disasmPane.innerHTML = html;
        document.getElementById("current-row")?.scrollIntoView({ block: "nearest" });
    }

    function renderStack(entries, pausedInWasm, note) {
        if (!entries || entries.length === 0) {
            // The operand stack is legitimately empty at most statement
            // boundaries — values accumulate between instructions of one
            // expression. Say so instead of looking broken; a note from the
            // static analyzer (reconstruction failure) takes precedence.
            stackPane.innerHTML = note
                ? '<div class="empty">' + esc(note) + '</div>'
                : pausedInWasm
                    ? '<div class="empty">Operand stack is empty at this instruction. Step by instruction inside an expression to see pushed values.</div>'
                    : '<div class="empty">No operand stack.</div>';
            return;
        }
        let html = '<table><tr><th>#</th><th>Type</th><th>Value</th></tr>';
        for (const e of entries) {
            html += "<tr><td>" + e.index + "</td><td>" + esc(e.type) + "</td><td>" + esc(e.value) + "</td></tr>";
        }
        html += "</table>";
        stackPane.innerHTML = html;
    }

    window.addEventListener("message", (event) => {
        const msg = event.data;
        if (msg.type === "update" && msg.state) {
            renderDisasm(msg.state.rows, msg.state.pauseOffset);
            renderStack(msg.state.entries, (msg.state.rows || []).length > 0, msg.state.stackNote);
        } else if (msg.type === "clear") {
            renderDisasm([], -1);
            renderStack([], false, undefined);
        }
    });
</script>
</body>
</html>`;
}

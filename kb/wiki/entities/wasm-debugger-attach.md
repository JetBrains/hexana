# Wasm debugger attach (Run | Attach to Process)

Hexana's Wasm debuggers can attach to a local process the IDE did not start, through the
platform's **Run | Attach to Process…** dialog (any IntelliJ-based IDE, including Rider/CLion).

## Where the code lives

- `plugins-shared/.../shared/WasmAttachTarget.kt`: pure command-line detection
  (`detectWasmAttachTarget(executableName, args)`), shared with VS Code and tested in commonTest
  `WasmAttachTargetTest`. IntelliJ-only plumbing is tested in `WasmAttachDebuggerProviderTest`.
- `idea-plugin/.../run/attach/WasmAttachDebuggerProvider.kt`: the
  `xdebugger.attachDebuggerProvider` (local host only). It builds the starter and starts the session
  with `startAttachedDebugSession` (`DebugSessionStartCompat.kt`: session builder on 261+,
  `startSessionAndShowTab` before that).

## Recognized targets

| Process | Endpoint read from | Debugger |
|---|---|---|
| `wasmtime … -g/--gdbstub <host:port>` | flag before the module | LLDB `process connect --plugin wasm` → `WasmtimeGdbDebugProcess` |
| `iwasm -g=<host:port>` | flag before the module | same → `WamrDebugProcess` |
| `node --inspect[-brk/-wait][=…]`, `--inspect-port` | node options before the script (default 9229) | `/json/list` → `CdpDebugProcess` |
| Chromium with `--remote-debugging-port=N` (not `--type=` helpers) | port, or `<user-data-dir>/DevToolsActivePort` when N is 0 | first `page` target from `/json` → `CdpDebugProcess` |

Endpoints on an OS-chosen port (`node --inspect=0`, `-g host:0`, `--remote-debugging-port=0`) are
returned with port 0 and `needsPortDiscovery`. See "OS-chosen ports" below. A gdbstub already held by
an open session is hidden, because a stub serves one client. Port-0 targets that are direct children of
the IDE are hidden too: they are Hexana's own launches (`--inspect-brk=0`, Hexana's Chrome), which
already have a session. Wildcard listen addresses map to `127.0.0.1`.

## Module / DWARF resolution

- The module path is the first `.wasm`/`.cwasm` argument, or a quoted `.wasm` path inside an inline
  `node -e` script (Hexana's Node runner). Relative paths are tried against `/proc/<pid>/cwd`
  (Linux only), then the project dir.
- LLDB sessions read DWARF from the stub regardless. The local file only adds the variable resolver
  and the Wasm disassembly tab.
- CDP sessions without a local module (`wasmFilePath == null`) build their resolvers from the first
  wasm script's `Debugger.getScriptSource` `bytecode` (`loadDebugInfoFromTargetIfNeeded`). This runs
  after `Debugger.enable`, which replays `scriptParsed` for modules that are already compiled, and
  before each breakpoint registration. Launched sessions keep using the file.

## Detach, not kill

- LLDB sessions: the runtime handler is `null`, so `detachOnStop` is true. The session handler and
  `stop()` send `process interrupt` (if running) + `process detach` + `quit` and destroy LLDB only after
  a 3 s grace period, so the detach packet reaches the stub. The launch flows are unchanged.
- CDP sessions use a `DetachedTargetProcessHandler` stand-in, and stopping only closes the WebSocket.

## OS-chosen ports

`plugins-shared/.../shared/ListeningPorts.kt` parses the process's listening TCP sockets:
- `lsof -nP -a -p PID -iTCP -sTCP:LISTEN -Fn` on macOS (`/usr/sbin/lsof`);
- `/proc/net/tcp*` joined with the socket inodes of `/proc/<pid>/fd` links on Linux, falling back to
  `lsof`;
- `netstat -ano` on Windows. A listening row is recognized by its `0.0.0.0:0` / `[::]:0` foreign
  address, because the state column is localized.

Hosts run these sources: `run/attach/ProcessListeningPorts.kt` in IntelliJ, `listeningEndpoints` in
VS Code. Then:
- **gdbstub:** not probed, since probing would take the single client slot. The runtime must listen on
  exactly one port, otherwise attach fails with the list of ports. wasmtime 44 itself prints
  "listening on 127.0.0.1:0", so discovery is the only way to find the real port.
- **Node:** probe `/json/list` on each port.
- **Chrome:** `DevToolsActivePort` first, then probe `/json` for a `page` on each port.

## Command lines

- **Exact argv where the OS has it:** `/proc/<pid>/cmdline` (Linux) is read instead of the space-joined
  listing (IntelliJ: `exactArguments`; VS Code: `detectProcess` → `detectAttachTargetFromArgs`).
- **Windows** lines keep their quotes but use backslash-literal rules: `splitWindowsCommandLine`
  (`CommandLineToArgvW` semantics). The older `splitOptions` treats `\` as an escape and mangles paths.
- **macOS `ps`** joins argv without quoting:
  - VS Code takes the executable path from `ps -o comm` and cuts it off verbatim
    (`detectWasmAttachTargetFromCommandLine(executable = …)`). IntelliJ's `ProcessInfo` already
    separates it.
  - A module path that got split is rejoined into `wasmPathCandidates`, longest first. Rejoining stops
    at options, `key=value` words and `host:port` words. Hosts take the first candidate that exists.
  - Node's inline runner path is found even after its quotes are stripped: `readFileSync(<path>.wasm)`.

## Page navigation (CDP)

Verified with headless Chrome: a top-level navigation sends `Runtime.executionContextsCleared`, not
`executionContextDestroyed`. `executionContextDestroyed` fires for iframes and workers too, so it now
ends the session only for Node.js.

On `executionContextsCleared`, browser sessions:
- forget the page's scripts, the wasm `scriptId` and the `wasmScriptSeen` flag (VS Code also clears
  its offset dedup map);
- drop debug info read from the previous page's bytecode.

The next page's module goes through the usual instrumentation pause, which re-registers the breakpoints.

## Open points

- VS Code attach: see [[entities/vscode-wasm-debugger-attach-plan]].
- No end-to-end UI test, and no live LLDB connect/detach yet.
- Stale CDP breakpoint ids from a previous page stay in the id maps. Removing such a breakpoint gets a
  CDP error, which is caught.
- Chrome attaches to the first `page` target; there is no picker for several tabs.

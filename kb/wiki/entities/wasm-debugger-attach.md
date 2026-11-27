# Wasm debugger attach (Run | Attach to Process)

Hexana's Wasm debuggers can attach to a local process the IDE did not start, through the
platform's **Run | Attach to Process…** dialog (any IntelliJ-based IDE, including Rider/CLion).

## Where the code lives

- `idea-plugin/.../run/attach/WasmAttachTarget.kt`: pure command-line detection
  (`detectWasmAttachTarget(executableName, args)`), tested in `WasmAttachTargetTest`.
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

Endpoints on an OS-chosen port that can't be found from outside are skipped, for example `node --inspect=0`,
which is what Hexana's own launches use. A gdbstub already held by an open session is hidden, because
a stub serves one client. Wildcard listen addresses map to `127.0.0.1`.

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

## Open points

- No end-to-end UI test yet. Coverage is the detection unit tests, plus the existing debug-process tests
  for the launch path.
- macOS `ps` output loses quoting, so a module path containing spaces may not be recovered.

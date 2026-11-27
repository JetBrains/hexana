# VS Code Wasm debugger attach

Status (2026-11-27): **implemented.** The page name is historical: this started as a plan.
IntelliJ's side is in [[entities/wasm-debugger-attach]]. Both hosts use one shared detector.

## Pieces

- **Shared detection:** `plugins-shared/.../shared/WasmAttachTarget.kt`
  - Provides `detectWasmAttachTarget(executable, args)`, `detectWasmAttachTargetFromCommandLine`
    (tokenized with `splitOptions`), `parseHostPort`, and `gdbStubEndpoint`.
  - `WasmAttachRuntime.id` is the VS Code `runtime` id. Tested in commonTest (`WasmAttachTargetTest`)
    on JVM and JS.
- **extension-lib:** `WasmAttach.kt` exports `detectAttachTarget(commandLine): JsWasmAttachTarget?`,
  typed in `src/extension-lib.d.ts`.
- **`src/WasmAttach.ts`**
  - `listProcesses`: `ps -axww -o pid=,args=`, or PowerShell `Get-CimInstance Win32_Process` on Windows.
  - `pickWasmProcess`: the `hexana.pickWasmProcess` QuickPick, hidden from the palette. It skips
    gdbstubs held by running `wasm` sessions, tracked through `onDidStart/TerminateDebugSession`.
  - `resolveAttachConfiguration`: `processId` → `runtime`/`host`/`port`/`userDataDir`/`wasmFile`.
    A `wasmFile` that doesn't exist is dropped instead of failing the attach.
  - `resolveCdpAttachWebSocketUrl`: Node `/json/list`; browser `/json` first `page`, using
    `DevToolsActivePort` when the port is 0.
  - `resolveModulePath`: `/proc/<pid>/cwd` (Linux), then the workspace folder.
- **`WasmDebugAdapter.ts`**
  - `resolveDebugConfiguration` lets `attach` through without `wasmFile`.
  - `resolveDebugConfigurationWithSubstitutedVariables` runs `resolveAttachConfiguration` after the
    picker's `${command:…}` has been substituted.
  - The factory's attach branch creates an `LldbDapProxy` with `setAttachEndpoint`, and nothing
    is launched.
- **`LldbDapProxy`:** `rewriteAttachRequest` (exported).
  - `attach` → `launch` + `launchCommands: [process connect --plugin wasm connect://host:port]` +
    `stopOnEntry`. This is the same request as the launched gdbstub flows, so lldb-dap behavior and
    the proxy's initial-stop handling are shared. The response's `command` is renamed back to `attach`.
  - `disconnect` gets `terminateDebuggee: false`, so lldb-dap detaches.
  - No runtime process is registered, so `dispose` kills only lldb-dap.
- **`CdpDebugAdapter`:**
  - `attach` → `handleAttach`, which connects to `config.wsUrl` and spawns nothing; `shutdown`
    therefore only closes the WebSocket.
  - Module loading is factored into `loadModuleBytes`.
  - `loadDebugInfoFromTargetIfNeeded` runs for attach sessions without `wasmFile`: it reads
    `Debugger.getScriptSource` `bytecode` of the first wasm script after `Debugger.enable`
    and before each `setBreakpoints`.
- **`package.json`:** `configurationAttributes.attach`; snippets for Attach to Process
  (`"^\"\\${command:hexana.pickWasmProcess}\""`), gdbstub, Node.js and Chrome.

## Verified

- Unit tests: `test/src/unit/wasmAttach.test.ts`. They cover the proxy rewrite (the attach
  round-trip and an untouched launch), the ps/PowerShell row parser, `DevToolsActivePort`,
  ws-URL discovery against a fake `/json` server, module path resolution, and the bundled detector.
- Live smoke test (macOS): real `ps` output detected a `wasmtime run -g 127.0.0.1:P app.wasm` and a
  `node --inspect=127.0.0.1:P` process, and the real Node inspector's ws URL was discovered.
  wasmtime 44 prints the exact `process connect --plugin wasm connect://…` command the proxy sends.

## Still unverified

- No end-to-end attach in VS Code itself, and no live LLDB connect/detach. The managed
  wasi-sdk LLDB was SIGKILLed on launch in the agent sandbox. Still open: whether lldb-dap's
  initial stop for a `launch` + `process connect` on an already-waiting stub matches the
  launched flow, and whether wasmtime/WAMR keep running after `terminateDebuggee: false`.
- The former pitfalls (OS-chosen ports, lost quoting, Chrome navigation) are resolved in both hosts;
  see [[entities/wasm-debugger-attach]] for the design. They were verified live on macOS:
  - `wasmtime -g 127.0.0.1:0` with a module under `my wasm/`: the port came from `lsof` and the spaced
    path was resolved;
  - `node --inspect=127.0.0.1:0`: the port and ws URL were discovered;
  - headless Chrome emits `executionContextsCleared` on navigation.

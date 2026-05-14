---
title: Hexana Troubleshooting (0.9)
description: Common Hexana issues and how to resolve them.
version: "0.9"
---

# Troubleshooting

Common issues users hit with Hexana 0.9 and how to resolve them.

## A .wasm file opens in the wrong editor

Hexana registers `.wasm` as its own file type plus a `fileTypeOverrider` (`HexFileTypeOverrider`) that claims the extension even when another plugin tries to register it. If a `.wasm` opens in the platform's default binary viewer instead of Hexana:

1. Open **Settings → Plugins** and confirm Hexana is installed and enabled.
2. Open **Settings → Editor → File Types** and confirm `.wasm` is associated with the `wasm` file type, not `Files supported via TextMate bundles` or another fallback.
3. Restart the IDE.

If the issue persists, capture the IDE log (`Help → Show Log in Finder/Explorer`) — Hexana logs to the standard `idea.log` and `HexFileTypeOverrider` records its decisions there.

## A .wat file opens but actions like Run don't work

`.wat` is a text format — Hexana provides language support but does not execute WAT directly. To run, the file must be compiled to `.wasm` first. The `:graalwasm:wat2wasm` module provides this conversion at build time; from the IDE, you either:

- Use `wabt`'s `wat2wasm` CLI manually and open the resulting `.wasm`, or
- Convert via the wat2wasm-wrapper WASM module bundled with Hexana (`graalwasm/wat2wasm/src/main/resources/wat2wasm_full.wasm`). This path is used internally by Hexana for tests; a user-facing conversion action is not yet wired.

## The Run button is greyed out

The information bar's Run button activates only when a runtime is configured. Open **Settings → Build, Execution, Deployment → WASM Runtime** and ensure at least one of Wasmtime, WAMR, or GraalVM has a valid path. See [`run-and-debug.md`](run-and-debug.md).

## "Wasmtime not found" or similar at run time

Hexana invokes the runtime by absolute path. If the runtime was moved, upgraded, or its `PATH` location changed:

1. Re-open **Settings → Build, Execution, Deployment → WASM Runtime**.
2. Re-pick the correct path.
3. If you upgraded LLVM and the debugger now refuses to start, confirm `lldb --version` is 22.1 or newer (this is the requirement for the experimental debugger added in 0.9).

## The debugger never hits a breakpoint

The debugger requires DWARF debug information in the `.wasm`. Without DWARF, Hexana cannot map a WAT-line breakpoint back to a source location.

1. Confirm the `.wasm` was built with debug symbols (`cargo build` without `--release`, Clang/Emscripten with `-g`).
2. Open the Module tab and check the custom-section list for `.debug_info`, `.debug_abbrev`, `.debug_str`, `.debug_line`.
3. If DWARF is present but breakpoints still don't fire, the binary may have been stripped or compiled with mismatched source paths — check `WasmDwarfInjector` logs in `idea.log`.

GraalVM does not support the debugger in 0.9. Use Wasmtime or WAMR if you need to debug.

## "Missing WASM tools" notification appears on a .wat file

`MissingWasmToolsNotification` surfaces when Hexana cannot find the WASM tooling it needs to operate on a `.wat` file. Typically this means Hexana could not locate or initialise the bundled `wat2wasm` WASM module. Try:

1. Re-running `./gradlew :idea-plugin:buildPlugin` to confirm the bundled resources are correctly packaged.
2. If the issue happens with an installed marketplace build, file an issue at the Hexana issue tracker with the IDE log attached.

## MCP tools don't appear in my AI assistant

1. Confirm the bundled `com.intellij.mcpServer` plugin is enabled (`Settings → Plugins`).
2. Confirm the IDE's MCP endpoint is running (look for an MCP-related entry under **Settings → Tools**).
3. Confirm the AI assistant client is configured to connect to that endpoint.
4. Confirm a `.wasm` file is open in the IDE — Hexana's tools are useful only when a module is loaded.

If a specific tool is missing, the registered toolset name (`HexanaToolset`) and tool list are defined in `mcp/tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/HexanaTools.kt`. The order list (`HEXANA_TOOL_ORDER`) is the authoritative source — if it includes a tool that does not appear, the build would have failed at code-gen time.

## Java completion / inspections don't fire on my GraalWasm or Chicory code

These features require the Java module (`com.intellij.modules.java`). They work in IntelliJ IDEA, RustRover, and Android Studio. They do **not** work in IDEs without bundled Java support (e.g. PyCharm Community).

If they're not firing even in an IDE with Java:

1. Confirm Hexana is enabled.
2. Confirm `WasmExportIndex` and `JavaWasmReferenceIndex` are populated — open a `.wasm` file once to seed them, then revisit the Java source.
3. The completion is scoped to the closest sibling `Parser.parse(path)` / `Source.newBuilder` — if there is no such builder above the call site in the same method, scoped resolution will not have a target.

## Large .wasm files are slow

Modules above ~50 MB or with very large code sections may take seconds to fully parse the first time. Hexana streams sections via `ByteBuffer` zero-copy, but indexes (`WasmIndex`, `WasmExportIndex`, `DwarfIndex`) build on top of the parsed module and the first parse pays the indexing cost.

- The Skiko fixture (`~8 MB`, ~14,000 functions, ~140 OpenGL imports) is Hexana's primary stress test and opens in <1 second on a recent Mac after the first parse.
- `WatFileSizeChecker` and `WasmFileSizeChecker` may skip some expensive operations on very large files; this is intentional.

## Hex view selection doesn't behave as I expect

Selection support in hex and text panels was rewritten in 0.7.1. If you are running an older Hexana build, upgrade. If you are on 0.9 and selection is misbehaving:

1. Confirm the focus is in the panel you expect (text panel vs. hex panel).
2. Arrow keys, `Shift+Arrow`, `Cmd/Ctrl+A` should all work in both.
3. File an issue with a screen recording if specific patterns break.

## How do I file a bug?

The current issue tracker is `https://github.com/JetBrains/hexana/issues` (per the URLs in `idea-plugin/CHANGELOG.md`). Include:

- Hexana version (visible in **Settings → Plugins**).
- IDE name and version.
- OS.
- IDE log excerpt (`Help → Show Log…` → upload `idea.log`).
- A minimal `.wasm` reproducer if possible.

## See also

- [`getting-started.md`](getting-started.md), [`run-and-debug.md`](run-and-debug.md), [`mcp-tools.md`](mcp-tools.md), [`java-integration.md`](java-integration.md).

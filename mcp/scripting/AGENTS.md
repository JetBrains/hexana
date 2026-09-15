# AGENTS.md for `mcp/scripting`

This module contains the shared JVM-only Hexana scripting runtime. It executes JavaScript over a loaded
`BinaryProvider` through GraalJS and binds the script globals used by the product scripting surface.

The globals themselves are **not** defined here. `plugins-shared/.../shared/scripting/` owns the API surface
(`scriptBinaryApi`, `ScriptOutput`, `ScriptApiReference`) as a host-neutral `ScriptValue` tree; this module owns the
GraalJS adapter for it. That split is what makes a script behave identically in the IntelliJ plugin, the VS Code
webview, and the `run-script` CLI. The IntelliJ script tab delegates here rather than building its own context.

## Start Here

Read these files before structural changes:

- `build.gradle.kts`
- `src/main/kotlin/org/jetbrains/hexana/mcp/scripting/HexanaScriptRunner.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/scripting/ScriptValueGraalProxy.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/scripting/HostObjectProxy.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/scripting/ScriptBinaryFileOpener.kt`
- `../../plugins-shared/src/commonMain/kotlin/org/jetbrains/hexana/shared/scripting/ScriptBinaryApi.kt`
- `../core/src/main/kotlin/org/jetbrains/hexana/mcp/BinaryInputLoader.kt`
- `../../binaryProvider/src/commonMain/kotlin/org/jetbrains/hexana/providers/BinaryProvider.kt`

## Ownership Boundary

- Keep script execution, script-source loading, sandbox policy, script-output limits, streaming, and structured script
  results here.
- Keep the definition of what `binary`, `open`, `print`, `table`, and `row` mean in `plugins-shared`. Adding a script
  member here instead would reintroduce the per-host divergence this split removed.
- Keep public MCP tool registration, generated CLI command metadata, and transport wiring out of this module.
- Reuse `BinaryInputLoader` and `AllowedFilePolicy` for file/binary access decisions instead of adding separate path rules.
- Expose only deliberate script globals. Do not enable host class lookup, arbitrary host access, process creation, thread
  creation, native access, or GraalJS IO without an explicit product/security decision and focused tests.
- `ScriptBinaryFileOpener` backs the `open(path)` global and is the one place a script reaches the file system. It reads
  through `BinaryInputLoader` and `AllowedFilePolicy`, so it can only reach files the host would already have loaded as
  the caller's `binary`, and a configured allowed root confines it. What crosses into the script is a parsed
  `BinaryProvider`, not a file handle or arbitrary file text, and GraalJS IO stays off — `read()` and `load()` are still
  denied. Keep it that way: a second file-reading path would have its own rules and none of these guarantees.
- `HostObjectProxy` backs `binary.raw` and the fall-through for uncurated members. It is deliberate **read-only data**
  exposure of the binary the caller already supplied — public properties reflected into `ProxyObject`s, no methods, no
  class handles. It does not relax the sandbox: the context keeps `HostAccess.NONE`, no class lookup, no IO, no process
  or thread creation. Keep it that way.

## Testing

- Add focused tests under `src/test/kotlin` for script loading, limits, timeouts, streaming, and sandbox denial, and for
  the Graal binding of the shared surface (`HexanaScriptApiBindingTest`).
- `open(path)` needs coverage on both sides of its policy: that it reads an allowed file, and that an allowed-root
  restriction denies one outside it without leaking its bytes into the output.
- The shape of the API is covered in `plugins-shared` (`ScriptBinaryApiTest`, `ScriptApiReferenceTest`); do not duplicate
  those assertions here — cover the adapter and the sandbox instead.
- `HexanaScriptRunnerTest` is the back-compatibility gate for scripts written against the pre-v1 `binary`. It must keep
  passing unedited.
- Run `./gradlew :mcp:scripting:test` and `./gradlew :plugins-shared:allTests` after changes here.
- When a public caller starts using this module, also run the narrowest affected `mcp/server` or plugin tests.

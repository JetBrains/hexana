# AGENTS.md for `mcp/scripting`

This module contains the shared JVM-only Hexana scripting runtime. It executes JavaScript over a loaded
`BinaryProvider` through GraalJS and exposes the script globals used by the product scripting surface.

## Start Here

Read these files before structural changes:

- `build.gradle.kts`
- `src/main/kotlin/org/jetbrains/hexana/mcp/scripting/HexanaScriptRunner.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/scripting/BinaryProviderProxy.kt`
- `../core/src/main/kotlin/org/jetbrains/hexana/mcp/BinaryInputLoader.kt`
- `../../binaryProvider/src/commonMain/kotlin/org/jetbrains/hexana/providers/BinaryProvider.kt`

## Ownership Boundary

- Keep script execution, script-source loading, sandbox policy, script-output limits, and structured script results here.
- Keep public MCP tool registration, generated CLI command metadata, and transport wiring out of this module.
- Keep IntelliJ and VS Code UI integration out of this module until a caller-migration ticket intentionally moves it.
- Reuse `BinaryInputLoader` and `AllowedFilePolicy` for file/binary access decisions instead of adding separate path rules.
- Expose only deliberate script globals. Do not enable host class lookup, arbitrary host access, process creation, thread
  creation, native access, or GraalJS IO without an explicit product/security decision and focused tests.

## Testing

- Add focused tests under `src/test/kotlin` for script loading, `binary`/`print` behavior, limits, timeouts, and sandbox
  denial.
- Run `./gradlew :mcp:scripting:test` after changes here.
- When a public caller starts using this module, also run the narrowest affected `mcp/server` or plugin tests.

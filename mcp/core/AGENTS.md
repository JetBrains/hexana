# AGENTS.md for `mcp/core`

This module contains the extracted JVM-only MCP library for WebAssembly analysis. It owns Wasm-oriented MCP DTOs, query
functions, and shared rendering/domain helpers. It does not own IntelliJ registration, plugin.xml wiring, or
VirtualFile/path resolution.

## Start Here

Read these files before making structural changes:

- `build.gradle.kts`
- `src/main/kotlin/org/jetbrains/hexana/mcp/FunctionDomain.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/TypeDomain.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/utils.kt`

## Module Map

- `src/main/kotlin/org/jetbrains/hexana/mcp`:
    - shared Wasm-domain extraction helpers used by multiple tools.
- `src/main/kotlin/org/jetbrains/hexana/mcp/query`:
    - public query functions that turn parsed Wasm modules into MCP-ready result objects.
- `src/main/kotlin/org/jetbrains/hexana/mcp/model`:
    - serialized result DTOs returned by the query layer.

## Ownership Boundary

- Keep this module free of IntelliJ Platform APIs.
- Do not add `VirtualFile`, project lookup, plugin.xml registration, or MCP tool annotations here.
- IntelliJ-specific MCP integration stays in `idea-plugin/`.
- If a tool needs richer input/output schema descriptions, add annotated wrapper DTOs in `idea-plugin/` rather than
  coupling `mcp/core` result models to IntelliJ MCP annotations.
- If a change requires file/path resolution or IDE state, define a clean input for it here and keep the actual IDE
  bridge in `idea-plugin`.

## Dependencies

- Prefer existing Wasm abstractions from:
    - `encdec/CommonByteBuffer`
    - `wasmParser/WasmParser`
    - `wasmParser` section/opcode models
- This module is JVM-only for now. Do not introduce multiplatform setup unless the task explicitly requires it.

## Testing

- There are no dedicated tests in this module yet.
- While tests still live in `idea-plugin`, keep public APIs stable enough for:
    - `idea-plugin/src/test/kotlin/org/jetbrains/hexana/mcp`
    - `idea-plugin/src/test/kotlin/org/jetbrains/hexana/mcp/direct`
- Run `./gradlew :mcp:core:build` first, then the narrowest affected `idea-plugin` test coverage.

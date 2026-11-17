# AGENTS.md for `wasmParser`

This module is the shared parser library for WebAssembly binaries. It is Kotlin Multiplatform and is reused by the IntelliJ plugin and provider layers.

## Core Architecture

- Main entry point: `src/commonMain/kotlin/org/jetbrains/hexana/wasm/WasmParser.kt`
- The parser reads `CommonByteBuffer` from `encdec/`.
- It distinguishes:
  - core Wasm modules
  - component-model binaries
- Version/layer dispatch happens in `WasmParser.parse(...)`.

Main package layout:

- `wasm/sections/`: core Wasm sections and section-level decoding helpers
- `wasm/components/`: component-model section types and decoders
- `wasm/opcodes/`: opcode models and decoding logic

## Authoritative Parsing References

When changing parsing logic, read the official core spec first:

- Intro and semantic phases:
  - `https://webassembly.github.io/spec/core/intro/index.html`
  - `https://webassembly.github.io/spec/core/intro/overview.html`
- Binary format:
  - `https://webassembly.github.io/spec/core/binary/index.html`
- Validation:
  - `https://webassembly.github.io/spec/core/valid/index.html`
- Text format for `.wat` fixtures and expected syntax:
  - `https://webassembly.github.io/spec/core/text/index.html`

Practical mapping:

- Section IDs, encodings, LEB128/value decoding, and module layout:
  - use the Binary Format section
- Type rules and stack discipline assumptions:
  - use the Validation section
- Expected `.wat` fixture syntax and folded text forms:
  - use the Text Format section
- High-level parser pipeline:
  - follow the spec’s decode -> validate -> execute split from the Overview page

## Design Rules For Changes

- Keep binary decoding in this module, not in `idea-plugin/`.
- Prefer small buffer extension functions close to the section/type they decode.
- Preserve offset, size, and index-space information when adding new parsed structures; the UI and MCP layers depend on that metadata.
- If you touch core parsing logic, make sure component-model parsing still works.
- If you add a new Wasm concept that the UI or MCP layer needs, expose it here in a stable typed form rather than forcing downstream code to re-parse raw bytes.

## Important Neighbors

- `encdec/` provides `CommonByteBuffer` and platform-specific implementations.
- `binaryProvider/` consumes parsed results from this module.
- `idea-plugin/` builds UI, navigation, and MCP features on top of the types defined here.

## Tests And Test Data

Primary tests:

- `src/commonTest/kotlin/org/jetbrains/hexana/wasm/WasmParserTests.kt`

Platform-specific helpers:

- `src/commonTest/kotlin/org/jetbrains/hexana/tests/*`
- `src/jvmTest/kotlin/org/jetbrains/hexana/tests/*`
- `src/webTest/kotlin/org/jetbrains/hexana/tests/*`

Checked-in test resources live under:

- `src/commonTest/resources/snippets/`: many small `.wat` / `.wasm` opcode fixtures
- `src/commonTest/resources/component/`: component-model fixtures
- `src/commonTest/resources/kotlin/`, `threads/`, `maps/`, `c/`: larger real-world fixtures and source-map cases

## Testing Guidance

- For parser logic changes, run the narrowest relevant target first:
  - `./gradlew :wasmParser:jvmTest`
  - `./gradlew :wasmParser:jsBrowserTest`
  - `./gradlew :wasmParser:wasmJsBrowserTest`
- If the parser change is consumed by plugin UI or MCP code, also run affected `idea-plugin` tests.
- Prefer adding focused new tests instead of only expanding broad golden assertions.

One practical note:

- `WasmParserTests.kt` is already large. If your new coverage is conceptually separate, prefer adding a new test file instead of growing that file further.

## Typical Change Paths

### New Section Or Record Support

- Update the relevant section class in `wasm/sections/` or `wasm/components/`.
- Wire resolution through `WasmParser` if a new section ID or dispatch case is involved.
- Add typed models first, then update downstream UI/MCP consumers.

### Opcode Decoding Changes

- Touch `wasm/opcodes/` and the section decoders that consume opcodes.
- Add snippet-based test resources when possible.
- Verify instruction ordering and structured block decoding, not only single opcodes.

### Name, Index, Or Range Metadata

- Be careful: downstream code uses offsets and unified index mapping for navigation, structure view, and MCP responses.
- When changing index handling, audit `idea-plugin/src/main/kotlin/org/jetbrains/hexana/mcp/` and `binaryProvider/`.

## Do Not

- Do not push parsing shortcuts into plugin code when the real fix belongs here.
- Do not introduce JVM-only assumptions into `commonMain` without a real multiplatform fallback.
- Do not silently drop parse metadata that is already consumed downstream.

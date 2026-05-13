# plugins-shared Module

Read the root `AGENTS.md` first, then this file.

## What This Module Contains

Shared, pure-Kotlin utility logic used by both `idea-plugin` and `vscode-plugin:webview`.  
This module has **no UI framework dependency** — no Compose Desktop, no Compose HTML, no IntelliJ Platform APIs.

The module provides:

- **Component description formatting** (`ComponentDescriptionFormat.kt`) — string representations for component sort indices and extern descriptions.
- **Wasm type formatting** (`WasmTypeFormat.kt`) — value type names, export/import kind labels, signature resolution and formatting.
- **Component resolve utilities** (`ComponentResolveUtil.kt`) — WASI CLI run detection, component function export detection, invoke name resolution, component type utilities.
- **WAT text generation** (`WatTextGenerator.kt`) — structural WAT-like text output from parsed core Wasm and component-model binaries.
- **Wasm run utilities** (`WasmRunUtil.kt`) — runtime import detection, component import/export name extraction, binary type detection, version-name splitting.
- **Wasm dependency resolver** (`WasmDependencyResolver.kt`) — platform-independent filesystem-based component dependency resolution with `WasmFileSystem` abstraction; used by both `idea-plugin` (JVM `java.io.File` adapter) and `vscode-plugin:extension-lib` (Node.js adapter).
- **Wasm runtime resolver** (`WasmRuntimeResolver.kt`) — platform-agnostic runtime executable resolution (maps runtime identifiers to executable paths) and unified runtime command-line dispatcher; used by both `idea-plugin` and `vscode-plugin:extension-lib` to avoid duplicating the runtime → executable mapping logic.
- **Wasm component composer** (`WasmComponentComposer.kt`) — platform-agnostic component composition orchestration using `wasm-tools compose` / `wac plug` with iterative multi-pass support; defines `ProcessRunner`, `ToolResolver`, and `ComposerFileAccess` abstractions that each platform implements. Used by both `idea-plugin` (JVM `ProcessBuilder` adapter) and `vscode-plugin:extension-lib` (Node.js `child_process` adapter).
- **Import stub generation** (`WasmImportStubGenerator.kt`, `WasmBinaryWriter.kt`) — generates stub Wasm modules for missing imports.
- **LLDB output parsing** (`LldbOutputParser.kt`) — pure parsers for LLDB stop events, expression results, frame locations, function names, and WAMR debug support detection; used by both `idea-plugin` and `vscode-plugin:extension-lib`.
- **DWARF path matching** (`DwarfPathMatcher.kt`) — platform-agnostic utilities for matching DWARF debug-info file paths against IDE/editor breakpoint paths, compilation-unit path heuristics, crate name extraction, and CU hash suffix stripping; used by both `idea-plugin` and `vscode-plugin:extension-lib`.

## KMP Targets

- JVM (consumed by `idea-plugin`)
- JS (consumed by `vscode-plugin:webview`)
- WasmJS

## Dependencies

- `:wasmParser`
- `:binaryProvider`
- `:encdec`

## Rules

- Keep all code platform-agnostic in `commonMain`. Do not add platform-specific source sets unless absolutely necessary.
- Do not add any UI framework dependencies (Compose, Jewel, IntelliJ Platform, HTML DOM).
- All production code uses the `org.jetbrains.hexana.shared` package prefix.
- When adding new shared logic, prefer pure functions over stateful classes.
- Both `idea-plugin` and `vscode-plugin:webview` maintain thin delegation wrappers for backward compatibility — update those when adding or changing public API here.

## Testing

- Run shared module tests: `./gradlew :plugins-shared:build`
- After changes, also run: `./gradlew :idea-plugin:test` and `./gradlew :vscode-plugin:webview:compileKotlinJs`

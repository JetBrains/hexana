---
title: Hexana Plugin Architecture (0.9)
description: Module layout, extension points, threading model, and code-generation pipeline for the Hexana IntelliJ plugin.
version: "0.9"
audience: contributors
---

# Architecture

This page describes the architecture of the Hexana IntelliJ plugin as of release 0.9. It is intended for contributors. For users, [`features.md`](features.md) is the right starting point.

## Repository layout

The Hexana repo is a multi-module Gradle build. The IntelliJ plugin (`:idea-plugin`) is one module among several.

```
Hexana/
├── analytics/                 # PostHog event logging
├── encdec/                    # LEB128 + binary encoding/decoding
├── wasmParser/                # core WASM binary parser
├── binaryProvider/            # ByteSource abstraction over .wasm files
├── plugins-shared/            # code shared between IntelliJ and VS Code plugins
├── mcp/
│   ├── core/                  # MCP protocol primitives + DSL
│   └── tools-generator/       # code-gen for HexanaToolset.kt
├── graalwasm/
│   ├── common/                # GraalWasm utilities
│   ├── wasmprinter/           # WAT rendering via the GraalWasm-bundled wasmprinter
│   └── wat2wasm/              # text → binary via wabt's wat2wasm compiled to WASM
├── idea-plugin/               # ← this module
└── vscode-plugin/             # VS Code companion
```

The IntelliJ plugin depends on `analytics`, `encdec`, `wasmParser`, `binaryProvider`, `plugins-shared`, `mcp:core`, and the three `graalwasm/*` modules.

## `idea-plugin` source layout

```
idea-plugin/
├── src/
│   ├── main/
│   │   ├── kotlin/org/jetbrains/hexana/   # main plugin sources
│   │   ├── java/                          # Java sources (rare; legacy)
│   │   ├── gen/                           # generated code (HexanaToolset.kt, WIT PSI)
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   ├── plugin.xml
│   │       │   ├── org.jetbrains.hexana.java.xml
│   │       │   └── org.jetbrains.hexana.javascript.xml
│   │       └── messages/HexanaBundle.properties
│   ├── test/
│   │   ├── kotlin/                        # unit + integration tests
│   │   └── data/                          # fixture .wasm/.wat/.wit
│   └── mcpTokenCounter/kotlin/            # tooling for counting MCP tool tokens
├── gradle/
│   └── mcp-token-counter.gradle.kts
├── QA/
└── build.gradle.kts
```

Generated sources under `src/main/gen/` are produced by the `:mcp:tools-generator` build and the WIT PSI generator. They are marked as `generatedSourceDirs` in the Gradle `idea` block so IntelliJ dims them and excludes them from Find-in-Path by default.

## Source package map

| Package | Responsibility |
|---|---|
| `org.jetbrains.hexana` (root) | `WasmFileType`, `WatFileType`, `WitFileType`, `BinaryFileType`, `HexanaFileEditorProvider`, the four file-based indexes. |
| `org.jetbrains.hexana.actions` | The four registered actions plus `HexFileTypeOverrider`. |
| `org.jetbrains.hexana.graalvm` | `GraalInstallationCustomizer` — discovers GraalVM installations on the host. |
| `org.jetbrains.hexana.javaLang` | Java-side WASM API support (GraalWasm + Chicory). Loaded conditionally via `org.jetbrains.hexana.java.xml`. |
| `org.jetbrains.hexana.javascript` | JS framework indexing for `instance.exports`. Loaded conditionally via `org.jetbrains.hexana.javascript.xml`. |
| `org.jetbrains.hexana.mcp` | MCP server integration (`McpServerCustomizer`, `IdeaMcpFileResolver`, `ToolsetHelpers`). Generated `HexanaToolset.kt` lives in `src/main/gen/`. |
| `org.jetbrains.hexana.navigation` | `HexanaGotoSymbolContributor`. |
| `org.jetbrains.hexana.run` | Run configurations, runtime command-line states, debug runner, breakpoint handler, DWARF injection, LLDB communicator. |
| `org.jetbrains.hexana.statistics` | FUS counter usages collector. |
| `org.jetbrains.hexana.wat` | WAT language support. |
| `org.jetbrains.hexana.wit` | WIT language support. |
| `org.jetbrains.hexana.wit.psi` | Generated WIT PSI tree and manipulators. |
| `org.jetbrains.hexana.wit.inspections` | The five WIT inspections. |
| `org.jetbrains.hexana.wit.formatter` | WIT formatter and code-style settings. |

## Extension points registered in `plugin.xml`

The plugin declares dependencies on `com.intellij.mcpServer`, Skiko, Compose desktop, and IntelliJ Platform's Compose / Jewel modules. It optionally consumes the JavaScript plugin and `com.intellij.modules.java`.

Roughly 50 extensions are registered. Categories:

- **File types and editors**: four `fileType`, one `fileEditorProvider`, one `fileTypeOverrider`, two `fileEditor.fileSizeChecker`.
- **WIT language**: parser definition, syntax highlighter, brace matcher, commenter, folding builder, breadcrumbs provider, highlight visitor, four element manipulators, rename input validator, find-usages factory, completion contributors, five inspections, formatter, code-style settings, line-marker provider, documentation provider, indexed roots provider.
- **WAT language**: parser definition, syntax highlighter, brace matcher, file-view-provider factory, problem-highlight filter, use-scope optimizer, documentation target provider, find-usages factory.
- **Indexes**: `WasmIndex`, `WasmExportIndex`, `DwarfIndex`, `WitComponentIndex`, `JavaWasmReferenceIndex` (Java module only).
- **Navigation**: `gotoSymbolContributor`.
- **Run / debug**: `configurationType`, `runConfigurationProducer`, `programRunner`, `xdebugger.breakpointType`.
- **MCP**: `mcpServer.mcpToolset`.
- **Settings**: two `applicationConfigurable`, one `applicationService`.
- **Statistics**: one `statistics.counterUsagesCollector`.
- **Notifications**: one `notificationGroup` (`hexana`), one `editorNotificationProvider`.
- **Compose runtime**: bridged via the platform Compose / Jewel modules.

## Threading model

Hexana follows IntelliJ Platform threading rules strictly:

- **EDT** for UI updates, dispatched via `invokeLater` / Compose's `LaunchedEffect`.
- **Pooled threads** (`executeOnPooledThread()`) and `Task.Backgroundable` for parsing, indexing, and runtime invocation.
- **`Dispatchers.IO`** inside Compose `LaunchedEffect` for binary reads, per the `BinaryProvider` contract.

Per `CLAUDE.md`: never block EDT, never call `java.io.File` directly in production paths, use `VirtualFile`. Section parsing in `wasmParser` is zero-copy over `ByteBuffer` regions — the WASM file may be 50 MB+ and Hexana must stream it lazily.

The combination of `BinaryProvider`'s `bytes` cursor plus Compose `LaunchedEffect { withContext(Dispatchers.IO) { … } }` plus multi-threaded lazy initialisation is a known source of bugs (see the `concurrency-detective` agent definition under `.claude/agents/`). When debugging a "code looks correct but state is wrong" issue, suspect shared mutable cursors before suspecting parser logic.

## Compose / Jewel UI

Hexana panels are written in Compose for Desktop and embedded in the IntelliJ Platform via the `intellij.platform.compose` and Jewel modules. The Compose runtime is provided by the platform — the `composeUI()` shorthand in `build.gradle.kts` pulls the right artifacts. Direct dependencies on `compose.desktop.currentOs` are `compileOnly` to avoid duplicating the runtime in the plugin distribution.

## Build pipeline

`./gradlew :idea-plugin:buildPlugin` produces `idea-plugin/build/distributions/hexana-<version>.zip`.

Key tasks:
- `:mcp:tools-generator:generateHexanaToolset` — runs the DSL → Kotlin generator and writes `idea-plugin/src/main/gen/org/jetbrains/hexana/mcp/HexanaToolset.kt`.
- `:idea-plugin:patchChangeLog` — uses the `org.jetbrains.changelog` Gradle plugin to insert a section for the current `pluginVersion` in `idea-plugin/CHANGELOG.md`.
- `:idea-plugin:publishPlugin` — publishes to JetBrains Marketplace. Uses the `release` or `nightly` channel based on the `hexanaBuild` Gradle property.
- `:idea-plugin:verifyPlugin` — IDE compatibility check.

Version computation reads `pluginVersion` from `idea-plugin/gradle.properties` (currently `0.9`) and combines it with `hexanaBuild`:

- `release` → `0.9`
- `nightly` → `0.9.<n>-nightly`
- otherwise → `0.9-SNAPSHOT`

## Code generation

Two generators run at build time:

1. **WIT PSI** — Grammar-Kit (or equivalent) generates the WIT parser and PSI element classes into `src/main/gen/org/jetbrains/hexana/wit/psi/`.
2. **MCP Toolset** — The DSL in `mcp/tools-generator/src/main/kotlin/.../HexanaTools.kt` produces a `List<ToolSpec>` that the generator turns into `HexanaToolset.kt`. The order of tools is enforced by `HEXANA_TOOL_ORDER`; the generator fails the build if a tool group adds a tool that is not in the order list, or vice versa.

## Testing

`idea-plugin/src/test/` contains both unit tests and platform integration tests (`BasePlatformTestCase`). Highlights:

- `mcp/` — one test per MCP tool plus contract suites (`HexanaMcpErrorContractTest`, `HexanaMcpStructuredOutputContractTest`, `HexanaMcpRegistrationTest`, `HexanaMcpDescriptionCoverageTest`).
- `wit/` — parser, formatter, completion, inspection, find-usages.
- `wat/` — parser, syntax, large-file handling.
- `javaLang/` — completion and inspection tests for GraalWasm and Chicory.

Run:
- `./gradlew :idea-plugin:test` — all tests.
- `./gradlew :idea-plugin:test --tests "*Wit*"` — subset.

## See also

- [`features.md`](features.md), [`run-and-debug.md`](run-and-debug.md), [`mcp-tools.md`](mcp-tools.md), [`java-integration.md`](java-integration.md), [`wit-language.md`](wit-language.md) — user-facing capability documentation.
- `CLAUDE.md` at the repo root — agent-facing project context.
- `.claude/agents/` — specialist agent definitions (concurrency, MCP DSL, WIT language, WASM parser, etc.) consulted during development.

# AGENTS.md

Read this file first, then read the nearest nested `AGENTS.md` for the module you are changing.

This repository is a JetBrains IntelliJ plugin workspace for WebAssembly analysis. The main product is the `idea-plugin` module. Most binary decoding logic lives in `wasmParser`, with small shared support modules around it.

## Persistent Storage — Knowledge Wiki (`kb/wiki/`)

All agents — the main session and every subagent it dispatches — MUST treat `kb/wiki/` as the durable, cross-session knowledge store, per `kb/CLAUDE.md` (the wiki schema + ingest/query workflow). This is persistent storage that survives across sessions, distinct from per-session memory.

- **Consult first.** Before substantive knowledge work (design, research, cross-module analysis, decisions), read `kb/wiki/index.md` to find and reuse relevant pages, then read those pages and their cited `(raw/…)` sources. Skip only for trivial mechanical edits with nothing relevant in the wiki.
- **Write back.** When you produce durable knowledge (a design, decision, source summary, or reusable finding), file it as `kb/wiki/sources/<slug>.md` or `kb/wiki/entities/<slug>.md`, update `kb/wiki/index.md`, and append one line to `kb/wiki/log.md`. Cross-link `[[entities/x]]`; cite raw inputs as `(raw/<file>)`.
- **Never modify `kb/raw/`** — those are read-only sources.
- Persist what is reusable across sessions; keep conversation-only scratch out of the wiki.
- When you dispatch a subagent, include this directive in its prompt so it consults and writes back to `kb/wiki/` too.

## Read Order

- Start here for repo-wide rules and commands.
- If you touch `idea-plugin/`, also read `idea-plugin/AGENTS.md`.
- If you touch `mcp/`, also read `mcp/AGENTS.md`.
- If you touch `mcp/core/`, also read `mcp/core/AGENTS.md`.
- If you touch `mcp/scripting/`, also read `mcp/scripting/AGENTS.md`.
- If you touch `mcp/server/`, also read `mcp/server/AGENTS.md`.
- If you touch `mcp/tools-generator/`, also read `mcp/tools-generator/AGENTS.md`.
- If you touch `mcp/vscode-server/`, also read `mcp/vscode-server/AGENTS.md`.
- If you touch `mcp/packaging/`, also read `mcp/packaging/AGENTS.md`.
- If you touch `wasmParser/`, also read `wasmParser/AGENTS.md`.
- If you touch `plugins-shared/`, also read `plugins-shared/AGENTS.md`.
- If you touch `ui-driver-tests/`, also read `ui-driver-tests/AGENTS.md`.
- If you touch `wasm-modules/`, also read `wasm-modules/AGENTS.md`.
- If you touch `graalwasm/` or `wasm-modules/`, read the embedded-converter notes in this file before editing.

## What This Repo Contains

- `idea-plugin/`: IntelliJ plugin implementation, Compose-based hex viewer, WAT/WIT language support, MCP toolset, plugin packaging.
- `mcp/`: parent folder for extracted MCP modules.
- `mcp/core/`: JVM-only Wasm MCP query/model library extracted from the plugin. It contains pure MCP-facing Wasm
  analysis logic, not IntelliJ registration or file-system integration.
- `mcp/scripting/`: JVM-only GraalJS scripting runtime for running Hexana scripts over loaded binary providers. It owns
  script source loading, script-output limits, output streaming, structured script results, and the GraalJS adapter for
  the shared script surface. The surface itself (`binary`, `print`/`table`/`row`) is defined in `plugins-shared`.
- `mcp/server/`: standalone Kotlin/JVM MCP runtime that exposes `mcp/core` over `stdio` and HTTP transports.
- `mcp/tools-generator/`: source of truth for Hexana semantic tool contracts, independent IDEA, standalone MCP, and
  CLI surface authorities, plus MCPB inventory derived from the standalone default profile (DSL + KotlinPoet generators
  for `idea-plugin`'s `HexanaToolset.kt` and `mcp/server`'s standalone generated files).
- `mcp/vscode-server/`: thin VS Code-facing MCP server distribution that launches the standalone runtime as a separate
  process for the VS Code extension.
- `mcp/packaging/`: thin wrapper layouts that point at the standalone runtime from `mcp/server/`.
- `cli/packaging/`: owns the public CLI downloads release bundle, immutable per-version ledger, and releases index
  from already verified native CLI ZIP artifacts; it does not build or mutate CLI application layouts.
- `wasmParser/`: Kotlin Multiplatform parser for core Wasm and component-model binaries.
- `parsing/`: parent folder for native binary parsing modules.
- `parsing/common/`: shared native parser primitives such as byte ranges, native word size/endian metadata, checked binary reading, parse diagnostics, unresolved records, and format detection.
- `parsing/elf/`: ELF parser module.
- `parsing/macho/`: Mach-O parser module.
- `parsing/pe/`: PE/COFF parser module.
- `disassembly/`: parent folder for native disassembly modules.
- `disassembly/core/`: UI-free native disassembly model: functions, instructions, addresses, xrefs, source locations, relocations, and basic-block candidates.
- `disassembly/llvm-objdump/`: JVM `llvm-objdump` command construction, candidate discovery, probing, execution result handling, output parsing, and conversion into the core disassembly model.
- `disassembly/native-fixtures/`: Docker-based generator for committed `llvm-objdump` disassembly snapshots built from `parsing/native-fixtures/test-data`.
- `binaryProvider/`: thin provider layer that wraps parsed Wasm and native binary data for the UI and navigation features. Native providers are a sealed hierarchy (`ElfBinaryProvider`, `MachOBinaryProvider`, `FatMachOBinaryProvider`, `PeBinaryProvider`, `CoffBinaryProvider`, `ArchiveBinaryProvider`) under `NativeBinaryProvider`; format detection is done by `NativeBinaryFormatDetector` and the factory `nativeOrGenericBinaryProvider`.
- `plugins-shared/`: Kotlin Multiplatform shared logic (no UI framework) used by both `idea-plugin` and `vscode-plugin:webview` — component description formatting, type labels, signature resolution, WAT text generation, table tab descriptors, and the custom-script-tab API surface (`shared/scripting/`) that every host binds.
- `cli/runtime/`: framework-neutral CLI runtime contracts for input conversion, semantic execution, output format selection, rendered payloads, source selections, and runtime context. It must not depend on MCP SDK, Ktor, Clikt, analytics, IntelliJ APIs, or server modules.
- `cli/metadata/`: neutral generated CLI catalog, input request adapters, and release-product metadata consumed by split CLI products. It depends only on `cli:runtime` and generated metadata must stay free of MCP SDK, Ktor, Clikt, analytics, IntelliJ APIs, and `mcp:server`.
- `cli/core/`: neutral CLI command orchestration and adapters for the standard `hexana-cli` product.
- `cli/scripting/`: neutral script-only CLI runtime contribution for `run-script`. It reuses `mcp:scripting` and
  `cli:runtime`, but stays free of standard command composition, MCP transports, Clikt, Ktor, analytics, IntelliJ APIs,
  and `mcp:server`.
- `cli/product/`: shared JVM product CLI layer for the split installed apps. It composes generated release-product
  selections with a small executor contract, and owns `HEXANA_CLI_*` env handling, output writing, exit mapping, and
  product telemetry without depending on standard or script command runtimes.
- `cli/hexana-cli/`: JVM application distribution for `summarize-binary`, `summarize-module`, and `wasm-to-wat`, plus
  product `tools`, `version`, and `help`. It provides the standard product executor.
- `cli/hexana-cli-script/`: JVM application distribution for `run-script`, plus product `tools`, `version`, and `help`.
  It provides the script product executor.
- `cli/integration-tests/`: test-only black-box support for installed CLI products: process execution, deterministic
  environment setup, local telemetry fixtures, and focused runner tests. Product behavior contracts live with their
  respective `hexana-cli` and `hexana-cli-script` distributions.
- `encdec/`: shared `CommonByteBuffer` abstraction and platform-specific byte-buffer helpers.
- `graalwasm/`: JVM wrappers around embedded `.wasm` utilities used for built-in `wasm -> wat` and `wat -> wasm`.
- `wasm-modules/`: Rust sources and Docker build flow that produce the embedded `.wasm` artifacts consumed by `graalwasm/`.
- `ui-driver-tests/`: slow, external-integration UI tests that download IDE artifacts and install the plugin.
- `doc/`: reference material, not a maintained architecture guide.
- `analysis/`: local research/export scratch data. It is not part of the product build; do not edit it unless the task explicitly targets it.

## Repo-Wide Baseline

- Build JDK: 25 for `idea-plugin`; emitted plugin bytecode remains Java 21 compatible.
- Build tool: Gradle wrapper (`./gradlew`).
- Kotlin: 2.4.0.
- IntelliJ Platform Gradle Plugin: 2.18.1.
- Main plugin target: IntelliJ IDEA `2026.2`.
- Root dependency coordinates live in `gradle/libs.versions.toml`.
- Included modules are declared in `settings.gradle.kts`.

## Authoritative Specifications

For WebAssembly behavior, prefer the official WebAssembly core specification over blog posts or third-party summaries.

- Core spec entry point: `https://webassembly.github.io/spec/core/intro/index.html`
- Overview and semantic phases: `https://webassembly.github.io/spec/core/intro/overview.html`
- Binary format: `https://webassembly.github.io/spec/core/binary/index.html`
- Validation rules: `https://webassembly.github.io/spec/core/valid/index.html`
- Text format: `https://webassembly.github.io/spec/core/text/index.html`

Use these sections when changing parsing, validation assumptions, binary layout handling, or WAT syntax support.

## Useful Commands

Run the narrowest command that proves your change.

- Start the plugin in a sandbox IDE: `./gradlew :idea-plugin:runIde`
- Run plugin tests: `./gradlew :idea-plugin:test`
- Build the extracted MCP core module: `./gradlew :mcp:core:build`
- Run the shared MCP scripting runtime tests: `./gradlew :mcp:scripting:test`
- Build the standalone MCP server: `./gradlew :mcp:server:build`
- Build the VS Code MCP server distribution: `./gradlew :mcp:vscode-server:installDist`
- Regenerate the idea-plugin toolset: `./gradlew :idea-plugin:generateHexanaToolset`
- Regenerate the standalone server generated files: `./gradlew :mcp:server:generateHexanaToolDispatcher`
- Build the plugin ZIP: `./gradlew :idea-plugin:buildPlugin`
- Build the FUS scheme artifact: `./gradlew :idea-plugin:buildEventsScheme`
- Run JVM parser tests: `./gradlew :wasmParser:jvmTest`
- Run native parser JVM tests: `./gradlew :parsing:common:jvmTest :parsing:elf:jvmTest :parsing:macho:jvmTest :parsing:pe:jvmTest`
- Run native disassembly tests: `./gradlew :disassembly:core:jvmTest :disassembly:llvm-objdump:test`
- Run JS browser parser tests: `./gradlew :wasmParser:jsBrowserTest`
- Run browser webapp tests: `./gradlew :webapp:jsBrowserTest -Phx.tests.web.enableChromeOrChromium=1`
- Run WasmJS browser parser tests: `./gradlew :wasmParser:wasmJsBrowserTest`
- Build shared module: `./gradlew :plugins-shared:build`
- Refresh the bundled WebAssembly feature catalog: `./gradlew :plugins-shared:updateBundledWasmFeatures`
- Build neutral CLI runtime contracts: `./gradlew :cli:runtime:build`
- Run neutral CLI core tests: `./gradlew :cli:core:test`
- Run installed standard CLI tests: `./gradlew :cli:hexana-cli:integrationTest`
- Run installed script CLI tests: `./gradlew :cli:hexana-cli-script:integrationTest`
- Run shared CLI process support tests (part of the TeamCity CLI JVM gate): `./gradlew :cli:integration-tests:test`
- Run the dedicated TeamCity CLI JVM gate: `./gradlew teamcityCliTests`
- Run the TeamCity-ready shared/core aggregate intended for the next rollout step: `./gradlew teamcityCoreTests`
- `teamcityCoreTests` also runs `teamcityFallbackCoreTests`: the source-owned Core fallback for unclassified,
  non-exempt Kotlin/JVM modules. It includes only each module's standard `:test` task; integration, UI-driver,
  native, browser-specific, packaging, and custom test tasks are never discovered automatically. Unclassified
  Kotlin Multiplatform projects remain explicit Core-topology debt until their `allTests` task is proven safe in
  the Core Docker lane.
- Run the TeamCity-ready VS Code plugin aggregate intended for the next rollout step: `./gradlew teamcityVscodePluginTests`.
  It runs both `:vscode-plugin:extension-lib:allTests` and `:vscode-plugin:webview:allTests`; do not replace the
  webview test surface with a compile-only task.
- Run the TeamCity-ready Webapp aggregate with production packaging and Chrome-backed browser tests:
  `./gradlew teamcityWebappTests`
- Run the dedicated TeamCity IntelliJ plugin test lane: `./gradlew teamcityIntellijPluginTests`
- Run the TeamCity-ready server and CLI aggregate intended for the next rollout step: `./gradlew teamcityServerCliTests`;
  it includes `teamcityCliTests` transitively plus the standalone MCP/server gates, including the R8 standalone,
  Claude, and Codex packaging smoke and extracted-release archive verification.
- Verify the exact standalone, Claude, and Codex R8 release ZIPs, their safe layouts, executable launchers, Java 21
  toolchain execution and compatibility, version output, and shared release-script security policy:
  `./gradlew :mcp:verifyMcpR8ReleaseArchives`.
- Run general TeamCity verification without CLI JVM gates: `./gradlew teamcityCiTests`. Its non-CLI task set remains
  unchanged during rollout and temporarily overlaps with the new core, VS Code, IntelliJ, and server aggregates.
- Direct `idea-plugin/**` changes select `InnoHub_HexFlow_IntellijPluginTestsPatronus`, which delegates to the regular
  IntelliJ Plugin Tests leaf.
- Core, IntelliJ, VS Code, Webapp, and Server/CLI are independent Patronus checks. Their generated path rules come from
  reverse transitive declared Gradle dependencies and lane seeds derived from the TeamCity aggregate task inventories,
  so one change can select multiple affected product lanes.
- Keep `.patronus/config.yaml` synchronized with the generated project-impact graph.
  `.patronus/config.yaml` is generated from `.patronus/patronus-config`; update it with
  `./gradlew updatePatronusConfig` and verify it with `./gradlew verifyPatronusConfigUpToDate`.
  `InnoHub_HexFlow_PatronusConfigCheckPatronus` is an ungrouped wildcard check for validating generated Patronus YAML.
  An unowned non-exempt Gradle project is conservatively assigned to Core. `ui-driver-tests` stays UI-only,
  `jvm-jit-jmh` selects IntelliJ verification without running benchmarks, and `.patronus/patronus-config` selects only
  freshness. Run `./gradlew verifyPatronusRoutingCoverage` after changing routing inputs.
- `./gradlew verifyPatronusImmediateNonUiLanePartition` proves the immediate non-UI Gradle partition of the former
  CI, CLI, and Webapp inventories. UI-driver work remains the `UiTestsPatronus` exception; Build Everything is
  scheduled-only, and VS Code TypeScript/Mocha is owned by its separate TeamCity composite rather than this Gradle
  task partition.
- Safe Merge is intentionally not selected by generated Patronus routing. The ungrouped Core, IntelliJ, VS Code,
  Webapp, and Server/CLI checks cover immediate non-UI product verification; `UiTestsPatronus` remains an explicit
  immediate UI exception. Documentation, tools, and other non-product roots are freshness-only before merge and are
  validated by scheduled Build Everything after merge.
- `./gradlew verifyPatronusProjectImpactGraph` exports and verifies a deterministic snapshot of declared Gradle project dependencies
  and aggregate-derived lane seeds to `build/patronus/project-impact-graph.tsv`. Update, verify, and test tasks regenerate
  and consume this snapshot without resolving configurations. The routing model and typed overlays live in
  `.patronus/patronus-config`.
  Snapshot project directories must resolve canonically inside the repository. Gradle project paths and configuration
  names use an explicit ASCII segment policy so TSV delimiters and control characters cannot be emitted.
- Run native standard CLI contracts (GraalVM 25+): `./gradlew :cli:hexana-cli:nativeIntegrationTest`
- Run native script CLI contracts (GraalVM 25+): `./gradlew :cli:hexana-cli-script:nativeIntegrationTest`
- Run TeamCity native integration surfaces: `./gradlew :mcp:assembleNativeMcpReleaseArtifacts`
- Run buffer abstraction tests: `./gradlew :encdec:test`
- Compile UI-driver tests: `./gradlew :ui-driver-tests:compileTestKotlin`
- Run UI-driver test module through the TeamCity aggregator:
  `./gradlew :ui-driver-tests:teamcityUiDriverTests`
- UI-driver tests remain explicit/daily and are not part of the dedicated test lanes above.

Existing IDE run configurations currently include:

- `Hexana [:idea-plugin:runIde]`
- `Hexana [:idea-plugin:buildPlugin]`
- `Hexana [:idea-plugin:test :graalwasm:wat2wasm:test]`
- `Hexana VSCode [build-and-run-locally.sh]`

## Working Rules

- Prefer module-scoped Gradle commands over workspace-wide builds.
- Prefer adding external dependency and plugin versions to `gradle/libs.versions.toml` and referencing them from module build scripts through `libs`.
- Add or update tests for behavior changes.
- Do not edit anything under `build/`, `.gradle/`, `.intellijPlatform/`, or `target/`.
- Keep changes inside the affected module unless the design actually crosses module boundaries.
- Prefer existing abstractions over ad hoc binary decoding:
  - `encdec/CommonByteBuffer`
  - `wasmParser/WasmParser`
  - `binaryProvider/*`
- All production code uses the `org.jetbrains.hexana` package prefix.

## Generated And Derived Files

- `idea-plugin/src/main/gen/**` holds generated output that is checked in. Do not hand-edit it as the primary source of truth.
  - WIT PSI/parser: grammar source `idea-plugin/src/main/java/org/jetbrains/hexana/wit/psi/wit.bnf`; no checked-in regen task.
  - `HexanaToolset.kt`: DSL source `mcp/tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/`;
    `HexanaTools.kt` composes canonical semantic contract order, feature files such as `FunctionTools.kt` and
    `DataTools.kt` own semantic inputs/results/descriptions, and `SurfaceAuthorities.kt` independently owns IDEA
    invocation bindings, standalone handlers/profiles, derived MCPB membership, and CLI bindings/products.
    Regenerate with `./gradlew :idea-plugin:generateHexanaToolset`.
- `mcp/server/src/main/gen/HexanaToolDispatcher.kt` is generated from resolved semantic contracts plus the standalone
  authority and contains standalone MCP tool registration plus MCP profile/default metadata.
- `cli/metadata/src/main/gen/HexanaCliToolset.kt` and `cli/metadata/src/main/gen/HexanaCliProducts.kt` are generated from
  resolved semantic contracts plus the independent CLI authority and contain CLI-visible metadata, opted-in input adapters, and release-product
  membership. Regenerate with `./gradlew :cli:metadata:generateHexanaCliMetadata`; verify with
  `./gradlew :cli:metadata:verifyHexanaCliMetadataUpToDate`.
- `mcp/server` generation intentionally emits only `HexanaToolDispatcher.kt`; CLI command construction stays outside
  the MCP server. If you change a semantic contract or CLI binding, edit its owning authority and rerun affected
  generators in the same change.
- `plugins-shared/src/commonMain/gen/.../BundledWasmFeatures.kt` embeds the committed
  `plugins-shared/features/features.json` snapshot (the offline fallback for the runtime
  compatibility table) as a Kotlin constant, because KMP resource loading is awkward on
  `wasmJs`. Regenerate with `./gradlew :plugins-shared:updateBundledWasmFeatures`; verify with
  `./gradlew :plugins-shared:verifyBundledWasmFeaturesUpToDate` (wired into `check`). If you
  replace the JSON snapshot, regenerate in the same change.
- Embedded converter binaries live here:
  - `graalwasm/wasmprinter/src/main/resources/wasmprinter_wrapper.wasm`
  - `graalwasm/wat2wasm/src/main/resources/wat2wasm_full.wasm`
- Those binaries come from Rust sources in `wasm-modules/`. If you change one side of that pipeline, update the other in the same change.

## Embedded Converter Pipeline

The built-in text/binary conversion path is split across two areas:

- Rust sources in `wasm-modules/wasmprinter_wrapper` and `wasm-modules/wat2wasm`
- JVM wrappers in `graalwasm/wasmprinter` and `graalwasm/wat2wasm`

Important contracts:

- `WasmPrinter.kt` expects exports named `memory`, `alloc_input`, and `print_wat`.
- `Wat2Wasm.kt` expects exports named `memory`, `alloc_input`, and `wat2wasm`.
- Both wrappers provide host callbacks through the `env` object.
- If the embedded `.wasm` shape changes, update the Kotlin wrapper code and tests together.

Docker build instructions for the Rust modules already exist in `wasm-modules/README.md`.

## Testing Expectations By Area

- `idea-plugin/` changes: run `:idea-plugin:test`.
- `mcp/core/` changes: run `:mcp:core:build` and the affected `:idea-plugin:test` coverage when the plugin bridge or
  tool contracts change.
- `mcp/scripting/` changes: run `:mcp:scripting:test`; also run the affected `mcp/server` or plugin coverage when a
  caller starts using the runtime.
- `cli/` changes: run `:cli:runtime:build`, `:cli:metadata:build`, `:cli:core:test`, `:cli:scripting:test`,
  `:cli:hexana-cli:integrationTest`, or `:cli:hexana-cli-script:integrationTest` as appropriate.
- `mcp/server/` changes: run `:mcp:server:build` and the narrowest affected `mcp/core` or plugin MCP coverage.
- `mcp/packaging/` changes: run `:mcp:assembleMcpPackaging` and smoke-test the affected packaged launcher under
  `mcp/build/packaging/*/bin/hexana-mcp version`.
- MCP downloads publication script changes: run `bash -n mcp/packaging/publish-downloads-release-bundle.sh` and
  `MCP_DOWNLOADS_BUNDLE_SELF_TEST=true bash mcp/packaging/publish-downloads-release-bundle.sh`.
- Codex packaging or repo-local marketplace/install-flow changes: also run `:mcp:installRepoLocalCodexPlugin` and the
  packaged MCP UI smoke test under `ui-driver-tests` when the change could affect Codex discovery, installation, or
  bundled skill/MCP wiring.
- `plugins-shared/` changes: run `:plugins-shared:build`, then `:idea-plugin:test` and `:vscode-plugin:webview:compileKotlinJs`.
- `wasmParser/` changes: run at least the relevant parser tests and any affected plugin tests.
- `parsing/` changes: run the affected `:parsing:*:jvmTest` task first; add JS/WasmJS checks when changing shared parser behavior that should work outside the JVM.
- `disassembly/` changes: run `:disassembly:core:jvmTest :disassembly:llvm-objdump:test`; keep IntelliJ-specific settings, CLion toolchain discovery, progress, and notification code in `idea-plugin`.
- `mcp/` changes in the plugin: run the direct MCP tests under `idea-plugin/src/test/kotlin/org/jetbrains/hexana/mcp/direct`.
- `graalwasm/` or embedded converter changes: run the directly affected plugin tests and the existing `:graalwasm:wat2wasm:test` run configuration if relevant.
- `ui-driver-tests/` is intentionally expensive. Do not run it unless your change affects plugin packaging, IDE startup, installation flow, or end-to-end UI behavior.

## Known Project Quirks

- `README.md` is mostly publishing-oriented and does not describe the implementation in enough detail. Trust code, build files, and nested `AGENTS.md` files over the README for architecture.

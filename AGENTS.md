# AGENTS.md

Read this file first, then read the nearest nested `AGENTS.md` for the module you are changing.

This repository is a JetBrains IntelliJ plugin workspace for WebAssembly analysis. The main product is the `idea-plugin` module. Most binary decoding logic lives in `wasmParser`, with small shared support modules around it.

## Read Order

- Start here for repo-wide rules and commands.
- If you touch `idea-plugin/`, also read `idea-plugin/AGENTS.md`.
- If you touch `wasmParser/`, also read `wasmParser/AGENTS.md`.
- If you touch `ui-driver-tests/`, also read `ui-driver-tests/AGENTS.md`.
- If you touch `wasm-modules/`, also read `wasm-modules/AGENTS.md`.
- If you touch `graalwasm/` or `wasm-modules/`, read the embedded-converter notes in this file before editing.

## What This Repo Contains

- `idea-plugin/`: IntelliJ plugin implementation, Compose-based hex viewer, WAT/WIT language support, MCP toolset, plugin packaging.
- `wasmParser/`: Kotlin Multiplatform parser for core Wasm and component-model binaries.
- `binaryProvider/`: thin provider layer that wraps parsed Wasm data for the UI and navigation features.
- `encdec/`: shared `CommonByteBuffer` abstraction and platform-specific byte-buffer helpers.
- `graalwasm/`: JVM wrappers around embedded `.wasm` utilities used for built-in `wasm -> wat` and `wat -> wasm`.
- `wasm-modules/`: Rust sources and Docker build flow that produce the embedded `.wasm` artifacts consumed by `graalwasm/`.
- `ui-driver-tests/`: slow, external-integration UI tests that download IDE artifacts and install the plugin.
- `doc/`: reference material, not a maintained architecture guide.
- `analysis/`: local research/export scratch data. It is not part of the product build; do not edit it unless the task explicitly targets it.

## Repo-Wide Baseline

- JDK: 21.
- Build tool: Gradle wrapper (`./gradlew`).
- Kotlin: 2.2.21.
- IntelliJ Platform Gradle Plugin: 2.10.5.
- Main plugin target: IntelliJ IDEA `2025.3`.
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
- Build the plugin ZIP: `./gradlew :idea-plugin:buildPlugin`
- Build the FUS scheme artifact: `./gradlew :idea-plugin:buildEventsScheme`
- Run JVM parser tests: `./gradlew :wasmParser:jvmTest`
- Run JS browser parser tests: `./gradlew :wasmParser:jsBrowserTest`
- Run WasmJS browser parser tests: `./gradlew :wasmParser:wasmJsBrowserTest`
- Run buffer abstraction tests: `./gradlew :encdec:test`
- Run UI-driver test module: `./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaWasmProjectDriverTest`

Existing IDE run configurations currently include:

- `Hexana [:idea-plugin:runIde]`
- `Hexana [:idea-plugin:buildPlugin]`
- `Hexana [:idea-plugin:test :graalwasm:wat2wasm:test]`

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

- `idea-plugin/src/main/gen/**` is generated WIT PSI/parser output. Do not hand-edit it as the primary source of truth.
- The WIT grammar source is `idea-plugin/src/main/java/org/jetbrains/hexana/wit/psi/wit.bnf`.
- The repo does not declare a checked-in Gradle task for regenerating WIT PSI. If you change the grammar, update the grammar sources and regenerated files together, and document how generation was done.
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
- `wasmParser/` changes: run at least the relevant parser tests and any affected plugin tests.
- `mcp/` changes in the plugin: run the direct MCP tests under `idea-plugin/src/test/kotlin/org/jetbrains/hexana/mcp/direct`.
- `graalwasm/` or embedded converter changes: run the directly affected plugin tests and the existing `:graalwasm:wat2wasm:test` run configuration if relevant.
- `ui-driver-tests/` is intentionally expensive. Do not run it unless your change affects plugin packaging, IDE startup, installation flow, or end-to-end UI behavior.

## Known Project Quirks

- `README.md` is mostly publishing-oriented and does not describe the implementation in enough detail. Trust code, build files, and nested `AGENTS.md` files over the README for architecture.

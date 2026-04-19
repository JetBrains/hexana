# AGENTS.md for `idea-plugin`

This module is the product. It contains the IntelliJ plugin, the custom editor, WAT/WIT language support, and the
IntelliJ-facing MCP bridge/tool registration layer.

## Start Here

Read these files before making structural changes:

- `build.gradle.kts`
- `src/main/resources/META-INF/plugin.xml`
- `src/main/kotlin/org/jetbrains/hexana/HexanaFileEditorProvider.kt`
- `src/main/kotlin/org/jetbrains/hexana/HexanaFileEditor.kt`
- `src/main/kotlin/org/jetbrains/hexana/HexanaService.kt`
- `src/main/gen/org/jetbrains/hexana/mcp/HexanaToolset.kt` (generated — do not hand-edit; DSL source is
  `../mcp/tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/HexanaTools.kt`)
- `src/main/kotlin/org/jetbrains/hexana/mcp/ToolsetHelpers.kt` (hand-written IntelliJ-side helpers the generated toolset calls)
- `../mcp/core/src/main/kotlin/org/jetbrains/hexana/mcp/query`

## Module Map

- Root package `org.jetbrains.hexana`:
  - file editor registration, settings, services, binary file types, indexing.
- `actions/`:
  - editor actions such as open-in-hex, show-in-hex, structure popup, goto offset.
- `providers/`:
  - `VirtualFile`-backed Wasm providers used by the editor and WAT view.
- `ui/` and `ui/panel/`:
  - Compose UI embedded into the IntelliJ editor UI.
- `wat/`:
  - WAT language support, syntax highlighting, navigation, resolve, inspections/filtering.
- `wit/`:
  - WIT language support, indexes, built-in definitions, documentation, breadcrumbs.
- `mcp/`:
    - IntelliJ-facing MCP bridge code only: tool registration, file resolution, and plugin integration.
- `sourcemap/`:
  - source-map parsing used to connect binary offsets back to source files.
- `statistics/`:
  - FUS usage collectors.

This module compiles sources from three locations:

- `src/main/kotlin`
- `src/main/java`
- `src/main/gen`

## Authoritative Registration Points

- `plugin.xml` is the source of truth for extension points, file types, actions, toolsets, and notifications.
- If a feature is not wired in `plugin.xml`, it does not exist at runtime.
- The plugin currently registers:
  - file types for `.wasm`, generic binaries, `.wat`, and `.wit`
  - the custom file editor
  - WAT and WIT parser/syntax/usage integrations
  - the MCP toolset
  - settings, notifications, and statistics collectors

## Editing Guidance By Area

### File Editor And UI

- The entry point is `HexanaFileEditorProvider`.
- `HexanaFileEditor` chooses a `BinaryProvider`, builds the Compose panel, and manages the WAT side view.
- The hex viewer UI is Compose, but it is hosted inside IntelliJ Swing components. Respect EDT/background boundaries already present in the code.
- If you add new tabs or binary panels, check `ui/panel/*`, `StructureView.kt`, and `HexViewStateHolder.kt` together.

### Binary Parsing And Navigation

- Do not decode raw bytes inside the UI layer if the logic belongs in `wasmParser/` or `binaryProvider/`.
- `BinaryProvider` is the bridge between parsed Wasm data and the plugin UI.
- If a feature needs new structural information from the binary, add it in the parser/provider layers first and then consume it here.

### WAT Support

- WAT support is mostly manual Java/Kotlin code under `src/main/java/.../wat/psi` and `src/main/kotlin/.../wat`.
- The WAT lexer and parser are not generated files in this repo. Treat them as maintained source.
- WAT navigation and resolve behavior lives in the Kotlin side under `wat/` and `wat/psi/`.
- For WAT grammar, tokens, and folded-instruction behavior, use the official WebAssembly text format as the reference:
  - `https://webassembly.github.io/spec/core/text/index.html`
- If you change syntax or PSI behavior, run the WAT lexer/parser/resolve tests.

### WIT Support

- WIT support mixes handwritten code and generated PSI/parser classes.
- Handwritten sources live in:
  - `src/main/java/org/jetbrains/hexana/wit/psi`
  - `src/main/kotlin/org/jetbrains/hexana/wit`
- Generated sources live in:
  - `src/main/gen/org/jetbrains/hexana/wit/psi`
- The grammar source is:
  - `src/main/java/org/jetbrains/hexana/wit/psi/wit.bnf`
- `src/main/gen/` also holds the generated `HexanaToolset.kt` (DSL source lives in `mcp/tools-generator/`). Regenerate via
  `./gradlew :idea-plugin:generateHexanaToolset`.
- Do not treat `src/main/gen` as the place to make the primary change.
- If you update WIT grammar or PSI contracts, update the handwritten grammar/mixin/util files and regenerate `src/main/gen` output in the same change.

### Built-In WIT Definitions

- Indexed built-in WIT files live under `src/main/resources/builtin/`.
- Both `wit02/` and `wit03/` are shipped and indexed recursively by `WitBuiltInDefinitionsContributor`.
- If you change built-in WIT content or version layout, keep both the resource tree and indexing assumptions in sync.

### MCP Tooling

- `HexanaToolset.kt` is the registration surface for all plugin MCP tools. It is generated from the DSL in
  `mcp/tools-generator/` — do not hand-edit it; regenerate with `./gradlew :idea-plugin:generateHexanaToolset`.
- `ToolsetHelpers.kt` is hand-written and holds the IntelliJ-side helpers the generated toolset calls
  (`requireWasmFile`, `contentsToCommonByteBuffer`, `requireParsedWasmModule`, range helpers).
- The extracted pure query/model library lives in the sibling `mcp/core` module.
- This module should keep only IntelliJ-specific MCP integration concerns such as:
    - `VirtualFile` and project-relative path resolution (in `ToolsetHelpers.kt`)
    - MCP plugin wiring through `plugin.xml`
- For tool semantics around binary/module structure, use the official core spec as the source of truth:
  - `https://webassembly.github.io/spec/core/intro/overview.html`
  - `https://webassembly.github.io/spec/core/binary/index.html`
  - `https://webassembly.github.io/spec/core/valid/index.html`

When adding or changing an MCP tool:

- Edit the DSL in `mcp/tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/HexanaTools.kt`; both the plugin
  toolset and the standalone-server dispatcher are generated from it.
- Put the real decode/query logic in the extracted `mcp/core` module, not in the descriptor body.
- Add or update a result model under the extracted `mcp/core` model package when the response shape is non-trivial.
- Prefer typed structured content over hand-built strings.
- Keep `@McpDescription` concise and token-aware. Do not embed example payloads, CLI walkthroughs, or field-by-field
  schema dumps in runtime tool descriptions; rely on generated schemas, model types, tests, and code comments for
  detailed structure.
- After DSL changes, run both `./gradlew :idea-plugin:generateHexanaToolset` and `:mcp:server:generateHexanaToolDispatcher`,
  and commit the regenerated `src/main/gen/` output together with the DSL change.
- Use the existing direct MCP test base to create `.wat` fixtures and call tools through the real MCP layer.

## Tests

Main test buckets:

- `src/test/kotlin/org/jetbrains/hexana/wat`: WAT lexer/parser/resolve tests.
- `src/test/kotlin/org/jetbrains/hexana/wit`: WIT lexer/parser tests.
- `src/test/kotlin/org/jetbrains/hexana/mcp`: lower-level Wasm/MCP tests.
- `src/test/kotlin/org/jetbrains/hexana/mcp/direct`: direct MCP registration and contract tests.

Testing conventions already used here:

- IntelliJ fixture tests use `BasePlatformTestCase`.
- Some simpler utility tests use `UsefulTestCase`.
- Direct MCP tests create fixtures at runtime rather than relying only on checked-in binaries.
- If you change tool payloads or errors, update the direct MCP tests first; they are the closest executable contract.

## Commands

- Run plugin tests: `./gradlew :idea-plugin:test`
- Launch sandbox IDE: `./gradlew :idea-plugin:runIde`
- Build plugin ZIP: `./gradlew :idea-plugin:buildPlugin`
- Build FUS scheme: `./gradlew :idea-plugin:buildEventsScheme`

## Known Constraints

- The plugin targets IntelliJ IDEA `2025.3`.
- It depends on bundled plugin `com.intellij.mcpServer`.
- Built-in Wasm-to-WAT conversion flows through `graalwasm`, not external CLI tools, in the current code path.
- `HexanaSettings.useBuiltInWasmTools` currently reads as always enabled.
- `polyglotimpl.DisableMultiReleaseCheck` is intentionally set for Graal/polyglot integration.

## Change Patterns That Usually Need Cross-Module Work

- New binary structure in the UI:
  - likely needs `wasmParser/` or `binaryProvider/` changes too
- New MCP tool over Wasm structure:
  - often needs `wasmParser/` updates plus plugin tests
- Changes to built-in conversion:
  - may require updates in `graalwasm/` and `wasm-modules/`
- WIT grammar changes:
  - require grammar source updates plus regenerated PSI

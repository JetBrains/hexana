# AGENTS.md for `mcp/tools-generator`

This module is the single source of truth for Hexana MCP tool definitions. Two KotlinPoet generators consume the same
DSL and emit checked-in Kotlin files into the two consumers:

- `idea-plugin/src/main/gen/org/jetbrains/hexana/mcp/HexanaToolset.kt` — `McpToolset` with 23 `@McpTool` methods, consumed
  by `plugin.xml`.
- `mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt` — `Server.registerHexanaTools(handlers)`
  extension for the standalone MCP runtime.

## Start Here

- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/Dsl.kt` — `class_`/`tools` builders, `FieldType` hierarchy,
  `FieldSpec` schema constraints, `ToolInputPattern`, and `ToolSpec`.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/CommonInputs.kt` — Wasm input and tiny shared input helpers.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/*Tools.kt` — feature-owned specs and definitions. Keep each tool's
  input specs, input pattern, result schema mirrors, descriptions, and handler FQN in the same feature file when possible.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/HexanaTools.kt` — catalog composition and canonical tool ordering.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/StandaloneToolsetMain.kt` — server dispatcher generator.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/IdeaToolsetMain.kt` — idea-plugin toolset generator.

## Ownership Boundary

- Tool descriptions, input/output shapes, and handler references live only in this module.
- Top-level tool output classes also declare their `mcp/core` model package here with `kotlinPackage = ...`; the
  idea-plugin generator must not carry a separate result-class package map.
- Standalone server input and output JSON schemas are generated from `ClassSpec`/`FieldSpec`; add defaults and bounds
  such as `minimum`, `maxItems`, and `maxLength` to the DSL instead of adding per-tool schema helpers in `mcp/server`.
- Idea-plugin method parameter defaults also come from `FieldSpec.default`; do not repeat numeric or boolean defaults in
  `IdeaToolsetMain.kt`.
- Generators must consume the full `handlerFqn`; do not hard-code `org.jetbrains.hexana.mcp.query` in generator code.
- `ToolInputPattern` validation must fail fast on field type, nullability, default, and description mismatches that would
  otherwise surface only during generator execution.
- No IntelliJ Platform APIs. No `mcp/core` model dependency — handlers are string FQNs, resolved at consumer compile time.
- The two generators share the same DSL but emit different targets; keep any new cross-cutting helpers next to the DSL,
  not inside a single generator.

## Regenerating

After editing the DSL, run both generators and commit their output alongside the DSL change:

- `./gradlew :idea-plugin:generateHexanaToolset`
- `./gradlew :mcp:server:generateHexanaToolDispatcher`

Neither generator is wired into `compileKotlin` automatically — the outputs are committed to `src/main/gen/` and
reviewed with the DSL change.

## Guidance prose conventions

- Reference tool names with backticks in summaries and guidance: `` `list_imports` ``, `` `get_instructions_for_functions` ``.
- Same convention for field names the caller has to pass: `` `entriesRange` ``, `` `funcidx` ``, `` `from` `` / `` `to` ``.
- Summaries are one line and already used standalone by some UIs — do not repeat them verbatim in the `guidance` block.
- Wrap multi-line guidance with a triple-quoted string and rely on the DSL's `trimIndent().trim()`; do not manually indent.

## Adding a New Tool

1. Add the output data class to `mcp/core/src/main/kotlin/org/jetbrains/hexana/mcp/model/**` and the handler function to
   `mcp/core/src/main/kotlin/org/jetbrains/hexana/mcp/query/**` (hand-written, not generated).
2. Mirror the output class shape with `class_(..., kotlinPackage = "...")` in the feature file that owns the tool, so
   the generator can emit an MCP output schema and the idea-plugin generator can import the real `mcp/core` return
   type. Add cross-feature reusable input pieces to `CommonInputs.kt`.
3. Set `inputPattern` beside `input`. If the input needs a new IDE method parameter pattern, add it to
   `ToolInputPattern` and update the pattern handling in `IdeaToolsetMain.kt`. The standalone dispatcher derives its
   input schema from `input`; update `StandaloneToolsetMain.kt` only when the DSL needs a new schema feature or field
   type. The existing patterns cover path-only, offset/limit, indices, closed range, and half-open segment range with
   filters.
4. Run both generators and both sets of tests: `./gradlew :mcp:tools-generator:test :idea-plugin:test :mcp:server:test
   :mcp:server:integrationTest`.

## Testing

- `./gradlew :mcp:tools-generator:test` — DSL self-checks (duplicate tool names, `describe`/serial-name coverage, etc.).
- Downstream verification: the idea-plugin `HexanaMcpDescriptionCoverageTest` and `:mcp:server:integrationTest` exercise
  the generated artifacts end-to-end.

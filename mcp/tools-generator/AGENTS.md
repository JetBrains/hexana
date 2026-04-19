# AGENTS.md for `mcp/tools-generator`

This module is the single source of truth for Hexana MCP tool definitions. Two KotlinPoet generators consume the same
DSL and emit checked-in Kotlin files into the two consumers:

- `idea-plugin/src/main/gen/org/jetbrains/hexana/mcp/HexanaToolset.kt` — `McpToolset` with 17 `@McpTool` methods, consumed
  by `plugin.xml`.
- `mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt` — `Server.registerHexanaTools(handlers)`
  extension for the standalone MCP runtime.

## Start Here

- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/Dsl.kt` — `class_`/`tools` builders, `FieldType` hierarchy, `ToolSpec`.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/HexanaTools.kt` — the DSL instance: every tool's name, summary,
  guidance, input class, output class, and handler FQN.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/Main.kt` — server dispatcher generator.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/ToolsetMain.kt` — idea-plugin toolset generator.

## Ownership Boundary

- Tool descriptions, input/output shapes, and handler references live only in this module.
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
2. Mirror the output class shape in `HexanaTools.kt` with `class_(...)` so the generator can emit an MCP output schema.
3. If the input needs a new parameter pattern, extend the `addParameters`/`addBody` switches in `ToolsetMain.kt` and the
   matching schema/dispatch code in `Main.kt`. The five existing patterns cover path-only, offset/limit, indices,
   closed range, and half-open segment range with filters.
4. Also add a `RESULT_PACKAGE` entry in `ToolsetMain.kt` mapping the new result class name to its `mcp/core/model/`
   package — this is what the generator uses to emit the correct import.
5. Run both generators and both sets of tests: `./gradlew :mcp:tools-generator:test :idea-plugin:test :mcp:server:test
   :mcp:server:integrationTest`.

## Testing

- `./gradlew :mcp:tools-generator:test` — DSL self-checks (duplicate tool names, `describe`/serial-name coverage, etc.).
- Downstream verification: the idea-plugin `HexanaMcpDescriptionCoverageTest` and `:mcp:server:integrationTest` exercise
  the generated artifacts end-to-end.

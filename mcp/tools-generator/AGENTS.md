# AGENTS.md for `mcp/tools-generator`

This module is the single source of truth for Hexana MCP tool definitions. Two KotlinPoet generators consume the same
DSL and emit checked-in Kotlin files into the two consumers:

- `idea-plugin/src/main/gen/org/jetbrains/hexana/mcp/HexanaToolset.kt` — `McpToolsProvider` with generated Hexana
  `McpTool` descriptors and call bodies, consumed
  by `plugin.xml`.
- `mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt` — `Server.registerHexanaTools(handlers)`
  extension, generated MCP profile membership, and generated input defaults for the standalone MCP runtime.
- `cli/metadata/src/main/gen/org/jetbrains/hexana/cli/metadata/HexanaCliToolset.kt` — generated CLI-visible tool
  metadata shared by split products and the legacy server CLI adapter.
- `mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaCliToolset.kt` — generated CLI tool command
  construction and executable CLI renderer dispatch for the standalone legacy server CLI.
- `cli/metadata/src/main/gen/org/jetbrains/hexana/cli/metadata/HexanaCliProducts.kt` — generated product identities,
  selected executable tools, output policy, and default output metadata.

A separate Claude Desktop MCPB manifest generator consumes catalog metadata and refreshes the checked-in packaging
template.

## Start Here

- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/Dsl.kt` — `class_`/`tools` builders, `FieldType` hierarchy,
  `FieldSpec` schema constraints, `ToolInputModel`/source-slot CLI input metadata, `ToolInputPattern`, and `ToolSpec`.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/CommonInputs.kt` — Wasm input and tiny shared input helpers.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/*Tools.kt` — feature-owned specs and definitions. Keep each tool's
  input specs, input pattern, result schema mirrors, descriptions, and handler FQN in the same feature file when possible.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/HexanaTools.kt` — catalog composition and canonical tool ordering.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/StandaloneToolsetMain.kt` — standalone server dispatcher
  and CLI command generator.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/IdeaToolsetMain.kt` — idea-plugin toolset generator.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/McpbManifestMain.kt` — Claude Desktop MCPB manifest generator.

## Ownership Boundary

- Tool descriptions, input/output shapes, and handler references live only in this module.
- Tool surface metadata also lives only in this module. Each tool must explicitly declare its MCP audience/profile intent,
  public MCPB manifest exposure, and CLI visibility, command name, group/domain, and output policy instead of deriving
  those facts from production profile membership.
- CLI release-product membership is catalog metadata independent from MCP profiles. TODO tools stay visible in the global
  CLI registry but must not belong to `hexana-mcp`, `hexana-cli`, or `hexana-cli-script` release selections.
- Public MCPB manifest exposure is generated from catalog metadata; do not maintain a separate manifest tool list.
- Top-level tool output classes also declare their `mcp/core` model package here with `kotlinPackage = ...`; the
  idea-plugin generator must not carry a separate result-class package map.
- Input/output conversion capability symbols are generator-local metadata on `ClassSpec`. `SemanticJson`, `McpWrapper`,
  `Pretty`, and `RawText` are implemented for the current POC paths. `RawBytes` is symbolic/deferred for future
  binary-safe rendering and is not exposed by current catalog tools. CLI source-slot input behavior belongs in
  `ToolInputModel` and its source/param specs; keep this as DSL metadata rather than duplicating it in generated or
  handwritten server code.
- Standalone server input and output JSON schemas are generated from `ClassSpec`/`FieldSpec`; add defaults and bounds
  such as `minimum`, `maxItems`, and `maxLength` to the DSL instead of adding per-tool schema helpers in `mcp/server`.
- Idea-plugin argument defaults also come from `FieldSpec.default`; do not repeat numeric, boolean, or string defaults in
  `IdeaToolsetMain.kt`.
- Generators must consume the full `handlerFqn`; do not hard-code `org.jetbrains.hexana.mcp.query` in generator code.
- `ToolInputPattern` validation must fail fast on field type, nullability, default, and description mismatches that would
  otherwise surface only during generator execution.
- No IntelliJ Platform APIs. No `mcp/core` model dependency — handlers are string FQNs, resolved at consumer compile time.
- The two generators share the same DSL but emit different targets; keep any new cross-cutting helpers next to the DSL,
  not inside a single generator.
- The standalone dispatcher intentionally emits one private registration helper per tool so each JSON schema stays below
  JVM method-size limits as the catalog grows.

## Regenerating

After editing the DSL, run the affected generators and commit their output alongside the DSL change:

- `./gradlew :idea-plugin:generateHexanaToolset`
- `./gradlew :mcp:server:generateHexanaToolDispatcher`
- `./gradlew :cli:metadata:generateHexanaCliMetadata`
- `./gradlew :mcp:generateClaudeDesktopMcpbManifestTemplate` when MCPB/public manifest surface metadata or summaries
  change; commit the refreshed `mcp/packaging/claude-desktop-mcpb/manifest.json` template if it changes.

These generation tasks are not wired into `compileKotlin` automatically — the checked-in outputs are committed and
reviewed with the DSL change.

Before sending a catalog change for review, run the generated-artifact drift checks:

- `./gradlew :idea-plugin:verifyHexanaToolsetUpToDate`
- `./gradlew :mcp:server:verifyHexanaToolDispatcherUpToDate`
- `./gradlew :cli:metadata:verifyHexanaCliMetadataUpToDate`
- `./gradlew :mcp:verifyClaudeDesktopMcpbManifestTemplateUpToDate`

## Guidance prose conventions

- Generated MCP tool descriptions are the tool `summary` followed by the
  optional `guidance` text. Keep both production-safe and neutral because model
  clients see the combined text in `tools/list`.
- Reference tool names with backticks in summaries and guidance: `` `query_artifacts` ``.
- Same convention for field names the caller has to pass: `` `operation` ``, `` `entityTypes` ``, `` `query` ``,
  `` `baselinePath` ``, and `` `startFuncidx` ``.
- Summaries are one line and already used standalone by some UIs — do not repeat them verbatim in the `guidance` block.
- Wrap multi-line guidance with a triple-quoted string and rely on the DSL's `trimIndent().trim()`; do not manually indent.

## Adding a New Tool

1. Add the output data class to `mcp/core/src/main/kotlin/org/jetbrains/hexana/mcp/model/**` and the handler function to
   `mcp/core/src/main/kotlin/org/jetbrains/hexana/mcp/query/**` (hand-written, not generated).
2. Mirror the output class shape with `class_(..., kotlinPackage = "...")` in the feature file that owns the tool, so
   the generators can emit MCP output schemas and structured-content encoding. Add cross-feature reusable input pieces
   to `CommonInputs.kt`.
3. Set `inputPattern` beside `input`. If the input needs a new IDE method parameter pattern, add it to
   `ToolInputPattern` and update the pattern handling in `IdeaToolsetMain.kt`. If the CLI needs path/stdin/base64/raw
   source selection or ordinary/JSON parameter binding, model that through `ToolInputModel` instead of adding ad hoc
   generated command code. The standalone dispatcher derives its input schema from `input`; update
   `StandaloneToolsetMain.kt` only when the DSL needs a new schema feature, field type, or generated CLI source behavior.
   The existing patterns cover path-only, offset/limit, indices, closed range, and half-open segment range with filters.
4. Set `surfaces` beside the tool contract. Choose the explicit MCP audience/profile, public MCPB manifest exposure,
   and CLI surface independently: public MCPB tools should use the public MCPB helper; visible CLI tools need a
   kebab-case command name, group/domain, and output policy; advanced-only or otherwise hidden CLI tools should use the
   hidden CLI helper.
5. Run both generators and both sets of tests: `./gradlew :mcp:tools-generator:test :idea-plugin:test :mcp:server:test
   :mcp:server:integrationTest`.

## Testing

- `./gradlew :mcp:tools-generator:test` — DSL self-checks (duplicate tool names, `describe`/serial-name coverage, etc.).
- Downstream verification: the idea-plugin `HexanaMcpDescriptionCoverageTest` and `:mcp:server:integrationTest` exercise
  the generated artifacts end-to-end.

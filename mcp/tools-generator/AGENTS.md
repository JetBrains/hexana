# AGENTS.md for `mcp/tools-generator`

This module owns the canonical semantic tool contract catalog and independent IDEA, standalone, and CLI surface
authorities. MCPB inventory is derived exactly from the standalone default profile. KotlinPoet
generators resolve contracts through the authority for their consumer and emit checked-in Kotlin files:

- `idea-plugin/src/main/gen/org/jetbrains/hexana/mcp/HexanaToolset.kt` — `McpToolsProvider` with generated Hexana
  `McpTool` descriptors and call bodies, consumed
  by `plugin.xml`.
- `mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt` — `Server.registerHexanaTools(handlers)`
  extension, generated MCP profile membership, and generated input defaults for the standalone MCP runtime.
- `cli/metadata/src/main/gen/org/jetbrains/hexana/cli/metadata/HexanaCliToolset.kt` — generated CLI-visible tool
  metadata shared by split products.
- `cli/metadata/src/main/gen/org/jetbrains/hexana/cli/metadata/HexanaCliProducts.kt` — generated product identities,
  selected executable tools, output policy, and default output metadata.

A separate Claude Desktop MCPB manifest generator consumes the standalone default profile in canonical contract order
and refreshes the checked-in packaging template.

## Start Here

- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/Dsl.kt` — `class_`/`tools` builders, `FieldType` hierarchy,
  `FieldSpec` schema constraints, typed `ToolId`, semantic-only `ToolSpec`, IDEA `ToolInputPattern`, and CLI input/binding
  types.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/CommonInputs.kt` — Wasm input and tiny shared input helpers.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/*Tools.kt` — feature-owned semantic contracts only: input specs,
  result schema mirrors, summaries, and guidance.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/HexanaTools.kt` — catalog composition and canonical tool ordering.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/SurfaceAuthorities.kt` — explicit ordered IDEA invocation bindings,
  standalone handler/profile authority, MCPB derivation, and CLI bindings/input models/products.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/StandaloneToolsetMain.kt` — standalone server dispatcher
  and neutral split-CLI metadata generator.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/IdeaToolsetMain.kt` — idea-plugin toolset generator.
- `src/main/kotlin/org/jetbrains/hexana/mcp/tools/generator/McpbManifestMain.kt` — Claude Desktop MCPB manifest generator.

## Ownership Boundary

- `ToolSpec` is semantic-only: typed ID, summary/guidance, input/output schema/defaults/bounds, and intrinsic conversion
  capabilities. Consumer invocation and membership policy must not be added back to feature contracts.
- IDEA owns invocation bindings (`ToolInputPattern` plus handler FQN) and selects the canonical standalone default profile. Standalone owns handler method bindings and explicit effective
  default/advanced/scripting lists. MCPB exactly reuses the standalone default selection. CLI owns command/group/output,
  renderer, explicit executable input models, and product selections.
- All authorities resolve typed IDs through `ToolCatalog`; duplicate/unknown selectors and missing/duplicate bindings
  fail before generation, and resolved output follows canonical contract order.
- CLI release-product membership is CLI authority metadata independent from MCP profiles. Every executable binding
  belongs to exactly one of `hexana-cli` or `hexana-cli-script`; TODO tools stay visible in the global CLI registry but
  belong to neither product. `hexana-mcp` is not a CLI release product.
- Public MCPB manifest exposure is generated from `HexanaStandaloneDefaultToolIds`; it has no independent membership policy.
- Top-level tool output classes also declare their `mcp/core` model package here with `kotlinPackage = ...`; the
  idea-plugin generator must not carry a separate result-class package map.
- Input/output conversion capability symbols are intrinsic reusable metadata on `ClassSpec`. `SemanticJson`, `McpWrapper`,
  `Pretty`, `Table`, and `RawText` are implemented for current CLI paths. `RawBytes` is symbolic/deferred for future
  binary-safe rendering and is not exposed by current catalog tools. CLI source-slot input behavior belongs only in
  CLI authority `ToolInputModel` bindings; executable CLI models must never be derived from IDEA patterns.
- Standalone server input and output JSON schemas are generated from `ClassSpec`/`FieldSpec`; add defaults and bounds
  such as `minimum`, `maxItems`, and `maxLength` to the DSL instead of adding per-tool schema helpers in `mcp/server`.
- Idea-plugin argument defaults also come from `FieldSpec.default`; do not repeat numeric, boolean, or string defaults in
  `IdeaToolsetMain.kt`.
- The IDEA generator consumes the full authority `handlerFqn`; do not hard-code `org.jetbrains.hexana.mcp.query`.
- `ToolInputPattern` validation must fail fast on field type, nullability, default, and description mismatches that would
  otherwise surface only during generator execution.
- No IntelliJ Platform APIs. No `mcp/core` model dependency — handlers are string FQNs, resolved at consumer compile time.
- Generators share semantic contracts but resolve different authorities; do not recreate a combined surface object or
  compatibility shim.
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
3. Add the tool's ordered IDEA binding in `SurfaceAuthorities.kt`. If it needs a new IDE method parameter pattern, add
   it to `ToolInputPattern` and update `IdeaToolsetMain.kt`. If the CLI needs path/stdin/base64/raw source selection or
   ordinary/JSON parameter binding, declare an explicit CLI `ToolInputModel`; never derive it from the IDEA binding.
   The standalone dispatcher derives its input schema from the semantic `input`; update
   `StandaloneToolsetMain.kt` only when the DSL needs a new schema feature, field type, or neutral CLI metadata behavior.
   The existing patterns cover path-only, offset/limit, indices, closed range, and half-open segment range with filters.
4. Update each applicable independent authority in `SurfaceAuthorities.kt`: IDEA selection/binding, standalone
   binding and effective profile lists, and CLI binding/product selections. MCPB follows standalone default membership
   automatically.
5. Run the affected generators and verification: `./gradlew :mcp:tools-generator:test :idea-plugin:test :mcp:server:test
   :mcp:server:integrationTest`, plus the generated-artifact drift checks above.

## Testing

- `./gradlew :mcp:tools-generator:test` — DSL self-checks (duplicate tool names, `describe`/serial-name coverage, etc.).
- Downstream verification: the idea-plugin `HexanaMcpDescriptionCoverageTest` and `:mcp:server:integrationTest` exercise
  the generated artifacts end-to-end.

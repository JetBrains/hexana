# Hexana Command-Line Product Ownership

## Decision

`hexana-mcp` is an MCP-only product. Its public command surface is exactly:

- `stdio`
- `http`
- `doctor`
- `version`
- `help`

It does not expose direct analysis commands or a `tools` catalog command. In particular, `summarize-binary`,
`summarize-module`, `wasm-to-wat`, `run-script`, and `tools` must fail as unknown `hexana-mcp` subcommands.

This product split does not remove MCP protocol tools. All configured `tools/list` and `tools/call` behavior remains in
the server, including the scripting-profile `run_binary_script` tool.

## Product Boundaries

`mcp:server` owns MCP transports, protocol tool registration and handlers, server profiles, diagnostics, versioning,
analytics, resource limits, and the five service commands above. Its production runtime must not import
`org.jetbrains.hexana.cli` or ship split CLI implementation archives.

The split applications own direct command execution:

- `hexana-cli` owns exactly `summarize-binary`, `summarize-module`, and `wasm-to-wat`, plus product-level `tools`,
  `version`, and `help` commands.
- `hexana-cli-script` owns exactly `run-script`, plus product-level `tools`, `version`, and `help` commands.

Shared CLI ownership remains layered:

- `cli:runtime` owns framework-neutral runtime contracts.
- `cli:metadata` owns generated CLI catalog metadata and the two split release-product selections.
- `cli:core` owns the three standard command converters, executors, and renderers.
- `cli:scripting` owns the isolated `run-script` contribution.
- `cli:product` owns shared split-product parsing, help, version, tools output, telemetry, and exit mapping.

## Generated Authorities

`mcp/tools-generator/.../SurfaceAuthorities.kt` keeps semantic MCP contracts independent from CLI release products.
Each executable CLI binding belongs to exactly one release product: `hexana-cli` or `hexana-cli-script`.
The CLI authority also owns source slots and option names. Generated neutral metadata supplies option types,
descriptions, defaults, constraints, source-choice explanations, and the `summarize-module` typed input validator to
the CLI runtime. Generated command help consumes this metadata. CLI binding examples are projected into help and the
developer catalog report, then checked by the generated decoder in `:cli:metadata:test`; they never execute tools.

The standalone generator emits only `HexanaToolDispatcher.kt` into `mcp:server`. Neutral CLI generation emits
`HexanaCliToolset.kt` and `HexanaCliProducts.kt` into `cli:metadata`; it must not emit a server CLI adapter.

## Packaging and Verification

Claude, Codex, MCPB, JVM, R8, and native `hexana-mcp` packages must preserve the MCP-only command surface. Package
smokes verify the five removed commands fail without writing protocol-unsafe stdout, while protocol tests continue to
exercise `tools/list`, `tools/call`, and the scripting MCP profile.

The installed MCP runtime and MCP R8 input must exclude `cli-core`, `cli-metadata`, `cli-runtime`, `cli-scripting`, and
`wasmprinter`. Split CLI R8/native archives retain the dependencies required by their own commands.

Breaking-proof coverage is the exact root-help inventory plus negative parsing and installed-package tests for all five
removed commands. Temporarily registering any removed command must make that coverage fail.

# AGENTS.md for `cli/core`

This module owns neutral CLI command orchestration and adapters that have a production consumer.

## Boundary

- Keep command implementation independent from `mcp:server`, Clikt, Ktor, MCP SDK, analytics, and IntelliJ Platform APIs.
- It may depend on `cli:runtime`, `graalwasm:wasmprinter`, and the extracted analysis/model layer in `mcp:core`.
- Standard analysis commands live here: `summarize-binary`, `summarize-module`, and `wasm-to-wat`. The legacy
  `hexana-mcp` server module may adapt parsed CLI state into this module, but must not own duplicate converters,
  executors, renderers, or output models for migrated standard commands.

## Verification

- Run `./gradlew :cli:core:test`.
- Keep `verifyCliCoreBoundary` wired into `check` so forbidden dependencies and imports fail early.
- Keep ownership coverage proving generated standard analysis metadata resolves through the neutral adapter.

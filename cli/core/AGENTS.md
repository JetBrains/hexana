# AGENTS.md for `cli/core`

This module owns neutral CLI command orchestration and adapters that have a production consumer.

## Boundary

- Keep command implementation independent from `mcp:server`, Clikt, Ktor, MCP SDK, analytics, and IntelliJ Platform APIs.
- It may depend on `cli:runtime`, neutral generated adapters in `cli:metadata`, `graalwasm:wasmprinter`, and the
  extracted analysis/model layer in `mcp:core`.
- Standard `hexana-cli` analysis commands live here: `summarize-binary`, `summarize-module`, and `wasm-to-wat`.
  `mcp:server` must not depend on this module or own duplicate direct-command adapters.
- `summarize-module` uses the generated `SummarizeModuleCliInputConverter` for request validation. Its executor keeps
  source materialization and runtime file limits in this module.

## Verification

- Run `./gradlew :cli:core:test`.
- Keep `verifyCliCoreBoundary` wired into `check` so forbidden dependencies and imports fail early.
- Keep ownership coverage proving generated standard analysis metadata resolves through the neutral adapter.

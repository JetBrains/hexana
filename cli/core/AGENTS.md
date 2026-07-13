# AGENTS.md for `cli/core`

This module owns neutral CLI command orchestration and adapters that have a production consumer.

## Boundary

- Keep command implementation independent from `mcp:server`, Clikt, Ktor, MCP SDK, analytics, and IntelliJ Platform APIs.
- It may depend on `cli:runtime` and the extracted analysis/model layer in `mcp:core`.
- `summarize-binary` lives here. The legacy `hexana-mcp` server module may adapt parsed CLI state into this module, but
  must not own a duplicate converter, executor, renderer, or output model for the migrated command.

## Verification

- Run `./gradlew :cli:core:test`.
- Keep ownership coverage proving generated summarize-binary metadata resolves through the neutral adapter.

# AGENTS.md for `cli`

This folder contains neutral CLI modules shared by current and future command-line entrypoints.

## Structure

- `runtime/`: framework-neutral contracts only. It may use Kotlin and semantic JSON types, but must not depend on MCP
  SDK, Ktor, Clikt, analytics, IntelliJ APIs, or `mcp:server`.
- `core/`: neutral command orchestration and adapters that are already exercised by production behavior. The
  `summarize-binary` implementation lives here; `mcp:server` is only a legacy adapter for it.
- `scripting/`: neutral script-only runtime contribution for `run-script`; it reuses `mcp:scripting` and
  `cli:runtime`, but must not import standard CLI command composition, MCP transports, Clikt, Ktor, analytics,
  IntelliJ APIs, or `mcp:server`.

## Verification

- Run `./gradlew :cli:runtime:build` after changing runtime contracts.
- Run `./gradlew :cli:core:test` after changing neutral adapters.
- Run `./gradlew :cli:scripting:test` after changing the script-only contribution.

# AGENTS.md for `cli/runtime`

This module contains framework-neutral CLI runtime contracts for source selection, input conversion, semantic execution,
output format selection, output rendering, and runtime context.

## Boundary

- Keep this module independent and implementation-free.
- Do not depend on MCP SDK, Ktor, Clikt, analytics, IntelliJ Platform APIs, `mcp:core`, `mcp:server`, or
  `mcp:tools-generator`.
- Public contracts may use `kotlinx.serialization.json` semantic JSON types.

## Verification

- Run `./gradlew :cli:runtime:build`.
- Keep `verifyCliRuntimeBoundary` wired into `check` so forbidden dependencies fail early.

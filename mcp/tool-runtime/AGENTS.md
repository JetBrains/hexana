# AGENTS.md for `mcp/tool-runtime`

This module contains runtime-facing contracts for CLI converters, semantic executors, and renderers.

## Boundary

- Keep this module independent and implementation-free.
- Do not depend on IntelliJ Platform, MCP SDK, Clikt, `mcp:core`, `mcp:server`, or `mcp:tools-generator`.
- Implementations for concrete Wasm, binary, Graal, CLI, or MCP server behavior belong in later modules or tickets.
- Public contracts may use `kotlinx.serialization.json` semantic JSON types.

## Verification

- Run `./gradlew :mcp:tool-runtime:build` after changes.
- Keep `verifyToolRuntimeBoundary` wired into `check` so forbidden dependencies fail early.

# AGENTS.md for `cli/scripting`

This module owns the neutral script-only CLI runtime contribution.

## Boundary

- Expose only `run-script` / `run_binary_script`.
- Reuse `cli:runtime` contracts and the shared `mcp:scripting` engine.
- Do not depend on `cli:core`, `mcp:server`, MCP SDK, Ktor, Clikt, analytics, or IntelliJ Platform APIs.
- Keep standard commands such as `summarize-binary`, `summarize-module`, and `wasm-to-wat` out of this module.

## Verification

- Run `./gradlew :cli:scripting:test`.

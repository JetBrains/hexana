# AGENTS.md for `mcp/server`

This module contains the standalone Hexana MCP runtime. It exposes `mcp/core` through process and HTTP transports and
must remain free of IntelliJ Platform APIs.

## Start Here

Read these files before making structural changes:

- `build.gradle.kts`
- `gradle.properties`
- `src/main/kotlin/org/jetbrains/hexana/mcp/server/Main.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/server/HexanaMcpServer.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/server/HexanaToolHandlers.kt`
- `../core/src/main/kotlin/org/jetbrains/hexana/mcp/HexanaAnalysisService.kt`

## Ownership Boundary

- Keep MCP transport wiring, CLI parsing, and tool registration here.
- Use the shared JVM CLI/logging stack here rather than ad hoc parsing or `System.err.println` wrappers.
- Keep Wasm parsing, query semantics, and result DTOs in `mcp/core`.
- Do not add IntelliJ APIs, `VirtualFile`, or plugin.xml-style registration here.
- The standalone MCP version is owned by `mcp/server/gradle.properties`, not by `idea-plugin/gradle.properties`.

## Transport Rules

- `stdio` must keep protocol traffic on stdout only. Human-readable logs belong on stderr.
- Keep CLI parsing library-backed and keep log formatting/configuration explicit so stdout remains protocol-only.
- Default HTTP bind should stay local-first (`127.0.0.1`) unless the CLI explicitly allows a public bind.
- Prefer the official MCP Kotlin SDK transport/runtime APIs over custom protocol plumbing.

## Testing

- Add focused server tests in this module for tool argument handling and runtime behavior.
- Use `src/integrationTest/kotlin` plus `./gradlew :mcp:server:integrationTest` for black-box process tests against the
  installed standalone distribution.
- Run `./gradlew :mcp:server:build` after changes here.
- If a change crosses the `mcp/core` boundary, also run `./gradlew :mcp:core:build`.

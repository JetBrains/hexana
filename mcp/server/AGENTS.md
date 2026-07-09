# AGENTS.md for `mcp/server`

This module contains the standalone Hexana MCP runtime. It exposes `mcp/core` through process and HTTP transports and
must remain free of IntelliJ Platform APIs.

For the current CLI ownership decision, read `../CLI_OWNERSHIP.md`: `hexana-mcp` is a real public CLI and remains in
this module together with the MCP server launcher until a later split ticket changes the artifact layout.

## Start Here

Read these files before making structural changes:

- `build.gradle.kts`
- `gradle.properties`
- `src/main/kotlin/org/jetbrains/hexana/mcp/server/Main.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/server/HexanaMcpServer.kt`
- `src/main/kotlin/org/jetbrains/hexana/mcp/server/HexanaToolHandlers.kt`
- `src/main/gen/org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt` (generated — do not hand-edit; DSL sources live
  under `../tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/`; includes standalone MCP tool registration
  plus MCP profile/default metadata)
- `src/main/gen/org/jetbrains/hexana/mcp/server/HexanaCliToolset.kt` (generated — do not hand-edit; includes
  CLI-visible tool metadata, CLI tool command construction, and executable CLI renderer dispatch)
- `../core/src/main/kotlin/org/jetbrains/hexana/mcp/HexanaAnalysisService.kt`

## Ownership Boundary

- Keep MCP transport wiring and CLI parsing here.
- Tool registration (`Server.registerHexanaTools`) is generated into `HexanaToolDispatcher.kt`; CLI-visible tool command
  construction and executable CLI renderer dispatch are generated into `HexanaCliToolset.kt`. To change a tool's name,
  description, input, output shape, or CLI surface, edit the DSL and regenerate with
  `./gradlew :mcp:server:generateHexanaToolDispatcher`; commit the regenerated files under `src/main/gen/`.
- The generated dispatcher also exposes `HexanaToolInputDefaults`; use those constants in hand-written argument parsing
  instead of repeating defaults from the DSL.
- Do not add hand-written per-tool input schema helpers here. Standalone input schemas are generated from
  `ClassSpec`/`FieldSpec` in `mcp/tools-generator`.
- Use the shared JVM CLI/logging stack here rather than ad hoc parsing or `System.err.println` wrappers.
- Keep Wasm parsing, query semantics, and result DTOs in `mcp/core`.
- Do not add IntelliJ APIs, `VirtualFile`, or plugin.xml-style registration here.
- The standalone MCP version is owned by `mcp/server/gradle.properties`, not by `idea-plugin/gradle.properties`.

## Transport Rules

- `stdio` must keep protocol traffic on stdout only. Human-readable logs belong on stderr.
- Keep CLI parsing library-backed and keep log formatting/configuration explicit so stdout remains protocol-only.
- Default HTTP bind should stay local-first (`127.0.0.1`) unless the CLI explicitly allows a public bind.
- Prefer the official MCP Kotlin SDK transport/runtime APIs over custom protocol plumbing.

## Analytics Policy

- Standalone MCP analytics stay enabled by default for JVM and GraalVM Native Image `stdio` and `http`; users can opt out with
  `--telemetry-enabled false` or `HEXANA_MCP_TELEMETRY_ENABLED=false`.
- PostHog networking and shutdown-hook behavior add runtime surface that can block native startup/smoke reliability, so
  keep native stdio smoke coverage on the default command line to verify the analytics-enabled native default remains
  stable.

## Testing

- Add focused server tests in this module for tool argument handling and runtime behavior.
- Use `src/integrationTest/kotlin` plus `./gradlew :mcp:server:integrationTest` for black-box process tests against the
  installed standalone distribution.
- Release packaging keeps two JVM fallback compatibility smoke gates:
  `./gradlew :mcp:assembleMcpPackagingPlain :mcp:server:plainMcpPackagingSmoke` for pre-R8 packages and
  `./gradlew :mcp:assembleMcpPackaging :mcp:server:obfuscatedMcpPackagingSmoke` for R8-obfuscated packages.
  Both gates exercise the standalone runtime plus Claude and Codex wrappers, but native matrix artifacts are required
  for release readiness.
- The experimental GraalVM Native Image prototype is intentionally separate from JVM/R8 packaging. Use
  `./gradlew :mcp:server:nativeCompile` to build `build/native/nativeCompile/hexana-mcp`, and
  `./gradlew :mcp:server:nativeMcpVersionSmoke` to verify `hexana-mcp version` reports the exact
  `mcp/server/gradle.properties:mcpServerVersion` when a GraalVM Native Image toolchain is available. Use
  `./gradlew :mcp:server:nativeMcpStdioSmoke` to run the native executable through
  the stdio MCP startup/tools/list/tool-call integration smoke.
- Run `./gradlew :mcp:server:build` after changes here.
- If a change crosses the `mcp/core` boundary, also run `./gradlew :mcp:core:build`.

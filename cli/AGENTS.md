# AGENTS.md for `cli`

This folder contains neutral CLI modules shared by current and future command-line entrypoints.

## Structure

- `runtime/`: framework-neutral contracts only. It may use Kotlin and semantic JSON types, but must not depend on MCP
  SDK, Ktor, Clikt, analytics, IntelliJ APIs, or `mcp:server`.
- `metadata/`: neutral generated CLI catalog and release-product metadata. It must depend only on `cli:runtime` at
  runtime and must not import MCP SDK, Ktor, Clikt, analytics, IntelliJ APIs, or `mcp:server`.
- `core/`: neutral command orchestration and adapters that are already exercised by production behavior. The standard
  analysis commands `summarize-binary`, `summarize-module`, and `wasm-to-wat` live here; `mcp:server` is only a legacy
  adapter for them.
- `scripting/`: neutral script-only runtime contribution for `run-script`; it reuses `mcp:scripting` and
  `cli:runtime`, but must not import standard CLI command composition, MCP transports, Clikt, Ktor, analytics,
  IntelliJ APIs, or `mcp:server`.
- `product/`: shared JVM product CLI layer for the split installed apps. It composes generated release-product
  selections with a small executor contract, owns `HEXANA_CLI_*` env handling, output writing, exit mapping, and
  product telemetry, and must not depend on standard or script command runtimes.
- `hexana-cli/`: JVM application for standard commands only: `summarize-binary`, `summarize-module`, and `wasm-to-wat`,
  plus `tools`, `version`, and `help`. It provides the standard product executor.
- `hexana-cli-script/`: JVM application for `run-script` only, plus `tools`, `version`, and `help`. It provides the
  script product executor.

## Verification

- Run `./gradlew :cli:runtime:build` after changing runtime contracts.
- Run `./gradlew :cli:metadata:build` after changing generated CLI metadata wiring.
- Run `./gradlew :cli:core:test` after changing neutral adapters.
- Run `./gradlew :cli:scripting:test` after changing the script-only contribution.
- Run `./gradlew :cli:hexana-cli:integrationTest` after changing installed standard CLI behavior.
- Run `./gradlew :cli:hexana-cli-script:integrationTest` after changing installed script CLI behavior.

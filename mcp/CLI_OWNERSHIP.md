# Hexana MCP CLI Ownership

## Decision

`hexana-mcp` is a real public CLI, not only a hidden MCP server executable. For now, one binary owns both public CLI
commands and MCP server launch commands.

Do not split the CLI into a new Gradle module or publish a separate artifact in MCP-69. The current package launchers
continue to invoke the same runtime binary, and the existing public launcher shape remains compatible:

```bash
hexana-mcp stdio
```

The `stdio` subcommand is the compatibility baseline for Claude, Codex, and other package launchers. Adding CLI
subcommands must not change the behavior of existing launcher templates that call `hexana-mcp stdio` unless a later
ticket intentionally updates the launcher contract and its smoke coverage.

## Current `mcp:server` Ownership

`mcp:server` remains the implementation owner for the current combined binary. It contains:

- process transport startup for `stdio`, including stdout reserved for MCP protocol traffic and human-readable logs on
  stderr;
- HTTP transport startup and local-first bind validation;
- the Clikt root command and service subcommand parsing for `hexana-mcp`;
- generated CLI command adapters from `HexanaCliToolset.kt`;
- direct executable CLI command dispatch for legacy commands that have not moved yet;
- generated MCP tool registration through `HexanaToolDispatcher.kt`;
- standalone analysis-service wiring to `mcp:core`;
- version, doctor, logging, analytics, and resource-limit command plumbing;
- the packaging entrypoint consumed by Claude, Codex, native-image, and JVM fallback package layouts.

This keeps the public CLI, server launcher, and packaging entrypoint in one reviewable runtime while the command surface
is still stabilizing.

`:cli:runtime` now owns the framework-neutral runtime contracts for input conversion, semantic execution diagnostics,
source selection, output format selection, rendered payloads, render options, and runtime context. `:cli:core` owns the
standard analysis command converters, executors, renderers, and neutral command adapter for `summarize-binary`,
`summarize-module`, and `wasm-to-wat`. `:cli:scripting` owns the isolated script-only `run-script` contribution and
delegates script execution to `mcp:scripting` without standard command composition or MCP transports. `mcp:server` keeps
only the legacy CLI parsing and final output-budget enforcement for these migrated commands; it must not duplicate their
converters, executors, renderers, or output models.

## Future Split Boundary

The catalog now declares three atomically versioned release products independently from MCP profiles: legacy
`hexana-mcp` keeps all four executable direct commands, `hexana-cli` selects the three non-scripting commands, and
`hexana-cli-script` selects only `run-script`. This is selection metadata only; the separate applications and artifacts
remain follow-up work.

Future split work should move contracts before moving artifacts. The likely boundary is:

- CLI runtime contracts for input conversion, output rendering, output format selection, rendered text/binary payloads,
  working-directory context, and environment access belong in `cli:runtime`;
- neutral standard command orchestration and already exercised non-scripting adapters belong in `cli:core`;
- the isolated script-only `run-script` adapter belongs in `cli:scripting`;
- generated metadata can continue to describe CLI-visible commands, but concrete executable command construction should
  eventually depend on those runtime contracts instead of server-only helpers;
- a future `mcp:cli` module may own public command composition, terminal rendering, and interactive input handling after
  the contracts are stable;
- `mcp:server` should keep MCP transports, MCP tool registration, server analytics, and server-specific resource
  policies after such a split;
- `mcp:packaging` should keep wrapper scripts and manifests only, forwarding to the selected binary without duplicating
  CLI or MCP runtime logic.

A future split must preserve package-launcher compatibility or provide a deliberate migration path. In particular,
existing packaged invocations of `hexana-mcp stdio` must keep working through the first split unless the launcher
contract is explicitly versioned and smoke-tested.

## Follow-up Tickets

Keep implementation tickets small enough for review:

1. Define the remaining CLI runtime contract gaps between current generated CLI adapters and `cli:runtime`, including
   terminal color behavior and any future binary-safe rendering.
2. Add focused compatibility tests that prove `hexana-mcp stdio`, `hexana-mcp version`, and implemented executable CLI
   tools still work through the packaged launcher.
3. Decide when generated CLI command construction should move behind a generated contract consumed by both `mcp:server`
   and a future `mcp:cli`.
4. Only after the contract layer is exercised, evaluate a separate `mcp:cli` module or artifact split and update
   package launcher smoke gates in the same implementation ticket.

## Breaking Proof

The MCP-69 ownership decision itself is documentation-only, but this branch now has executable impact through later
CLI work: generated CLI metadata marks `summarize-binary` executable, standalone runtime dispatch renders its JSON
output, Gradle drift checks cover generated tool files, and installed-runtime tests exercise the packaged launcher.

Breaking-proof coverage for this branch is:

- server and generator unit coverage for CLI metadata, generated files, schema compatibility, and runtime argument
  handling;
- generated artifact drift checks for both standalone MCP and IDEA toolset outputs;
- `cli/core` ownership coverage proving generated standard analysis metadata and the legacy server adapters resolve to
  the neutral adapter instead of server-local implementations;
- installed-runtime smokes for `hexana-mcp stdio`, `hexana-mcp version`, and executable public CLI commands including
  `hexana-mcp summarize-binary <file> --format json`, `hexana-mcp summarize-module <file> --format pretty`, and
  `hexana-mcp wasm-to-wat <file> --format raw`;
- package-level smoke gates when release packaging layouts or launcher scripts change.

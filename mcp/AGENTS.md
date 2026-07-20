# AGENTS.md for `mcp`

This folder groups extracted MCP modules.

For CLI ownership and split-boundary decisions, read `CLI_OWNERSHIP.md`.

## Read Order

- If you touch `mcp/core/`, also read `mcp/core/AGENTS.md`.
- If you touch `mcp/scripting/`, also read `mcp/scripting/AGENTS.md`.
- If you touch `mcp/server/`, also read `mcp/server/AGENTS.md`.
- If you touch `mcp/vscode-server/`, also read `mcp/vscode-server/AGENTS.md`.
- If you touch `mcp/tools-generator/`, also read `mcp/tools-generator/AGENTS.md`.
- If you touch `mcp/packaging/`, also read `mcp/packaging/AGENTS.md`.

## Structure

- `core/`: JVM-only Wasm MCP query/model library used by the IntelliJ plugin bridge in `idea-plugin/`.
- `scripting/`: JVM-only GraalJS runtime for Hexana script execution over `BinaryProvider` instances. It keeps script
  loading, sandbox policy, script-output limits, and structured script results separate from public MCP/CLI wiring.
- `server/`: standalone MCP runtime that serves the extracted Wasm tooling over `stdio` and HTTP. It keeps the legacy
  `hexana-mcp` CLI entrypoint but delegates migrated neutral CLI behavior to `cli/core`.
- `vscode-server/`: thin VS Code-facing MCP server distribution that runs as a separate stdio/http process for the
  VS Code extension while reusing `server/` runtime code.
- `tools-generator/`: canonical semantic contracts plus independent IDEA, standalone, MCPB, and CLI authorities, with
  KotlinPoet generators that emit the idea-plugin
  `HexanaToolset.kt`, standalone-server generated files such as `HexanaToolDispatcher.kt` and server CLI command
  construction, and neutral generated CLI metadata under `cli/metadata/src/main/gen`.
- `packaging/`: thin Claude/Codex wrapper layouts around the standalone runtime in `server/`.

## Boundary

- Keep folder-level organization concerns here.
- Put module-specific rules in the nested module `AGENTS.md`.

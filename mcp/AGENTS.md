# AGENTS.md for `mcp`

This folder groups extracted MCP modules.

## Read Order

- If you touch `mcp/core/`, also read `mcp/core/AGENTS.md`.
- If you touch `mcp/server/`, also read `mcp/server/AGENTS.md`.
- If you touch `mcp/tool-runtime/`, also read `mcp/tool-runtime/AGENTS.md`.
- If you touch `mcp/vscode-server/`, also read `mcp/vscode-server/AGENTS.md`.
- If you touch `mcp/tools-generator/`, also read `mcp/tools-generator/AGENTS.md`.
- If you touch `mcp/packaging/`, also read `mcp/packaging/AGENTS.md`.

## Structure

- `core/`: JVM-only Wasm MCP query/model library used by the IntelliJ plugin bridge in `idea-plugin/`.
- `server/`: standalone MCP runtime that serves the extracted Wasm tooling over `stdio` and HTTP.
- `tool-runtime/`: JVM-only runtime-facing contracts for CLI input conversion and output rendering.
- `vscode-server/`: thin VS Code-facing MCP server distribution that runs as a separate stdio/http process for the
  VS Code extension while reusing `server/` runtime code.
- `tools-generator/`: DSL declaring all Hexana MCP tools and KotlinPoet generators that emit the idea-plugin
  `HexanaToolset.kt` and the standalone-server `HexanaToolDispatcher.kt`.
- `packaging/`: thin Claude/Codex wrapper layouts around the standalone runtime in `server/`.

## Boundary

- Keep folder-level organization concerns here.
- Put module-specific rules in the nested module `AGENTS.md`.

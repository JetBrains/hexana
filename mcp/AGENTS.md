# AGENTS.md for `mcp`

This folder groups extracted MCP modules.

## Read Order

- If you touch `mcp/core/`, also read `mcp/core/AGENTS.md`.

## Structure

- `core/`: JVM-only Wasm MCP query/model library used by the IntelliJ plugin bridge in `idea-plugin/`.

## Boundary

- Keep folder-level organization concerns here.
- Put module-specific rules in the nested module `AGENTS.md`.

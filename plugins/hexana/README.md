# Hexana

Hexana adds WebAssembly inspection tools to Claude Code and Codex through a bundled MCP server.

## Requirements

- Java 21 or newer must be available on `PATH`.
- The plugin is installed from a configured Claude Code or Codex marketplace.

## Included Files

- `.claude-plugin/plugin.json`: Claude Code plugin manifest.
- `.codex-plugin/plugin.json`: Codex plugin manifest.
- `.claude-plugin/mcp.json` and `.codex-plugin/mcp.json`: client-specific MCP server configuration.
- `bin/hexana-mcp`: launcher used by the client plugin.
- `server/`: bundled standalone Hexana MCP runtime.
- `skills/wasm-inspector/`: Codex skill metadata and instructions.

## What It Provides

The bundled MCP server can summarize WebAssembly modules and list imports, exports, memories, globals,
types, functions, locals, instructions, element segments, and data segments.

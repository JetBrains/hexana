# Hexana

Hexana adds WebAssembly inspection tools to Claude Code and Codex through a bundled MCP server.

## Requirements

- macOS arm64 and Linux x64 use the bundled native executable by default.
- Other platforms require Java 21 or newer on `PATH` for the bundled JVM fallback.
- The plugin is installed from a configured Claude Code or Codex marketplace.

## Included Files

- `.claude-plugin/plugin.json`: Claude Code plugin manifest.
- `.codex-plugin/plugin.json`: Codex plugin manifest.
- `.claude-plugin/mcp.json` and `.codex-plugin/mcp.json`: client-specific MCP server configuration.
- `bin/hexana-mcp`: launcher used by the client plugin.
- `LICENSE.txt`: JetBrains plugin license agreement.
- `THIRD-PARTY-NOTICES.md`: generated third-party dependency notices.
- `server/`: bundled standalone Hexana MCP runtime.
- `skills/wasm-inspector/`: Codex skill metadata and instructions.
- `native/macos-arm64/hexana-mcp`: bundled native executable.
- `native/linux-x64/hexana-mcp`: bundled native executable.

## Native Artifacts

Native executables are bundled in this public marketplace tree for the supported native platforms:

- `native/macos-arm64/hexana-mcp`
- `native/linux-x64/hexana-mcp`

The marketplace launcher uses the bundled native executable when it matches the current platform, then falls
back to the bundled JVM runtime from `server/`.

## What It Provides

The bundled MCP server can summarize WebAssembly modules and list imports, exports, memories, globals,
types, functions, locals, instructions, element segments, and data segments.

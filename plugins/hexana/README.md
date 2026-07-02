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
- `LICENSE.txt`: JetBrains plugin license agreement.
- `THIRD-PARTY-NOTICES.md`: generated third-party dependency notices.
- `server/`: bundled standalone Hexana MCP runtime.
- `skills/wasm-inspector/`: Codex skill metadata and instructions.

## Native Artifacts

Native executables are not included in this public marketplace tree. They are published as GitHub Release
artifacts produced by the platform matrix:

- `hexana-mcp-native-macos-arm64-0.3.0.zip`
- `hexana-mcp-native-linux-x64-0.3.0.zip`
- `hexana-claude-plugin-macos-arm64-0.3.0.zip`
- `hexana-claude-plugin-linux-x64-0.3.0.zip`
- `hexana-codex-plugin-macos-arm64-0.3.0.zip`
- `hexana-codex-plugin-linux-x64-0.3.0.zip`

The marketplace launcher uses the bundled JVM runtime from `server/`. Native-enabled release ZIPs use the
same launcher and add `native/<platform>/hexana-mcp` beside the JVM fallback.

## What It Provides

The bundled MCP server can summarize WebAssembly modules and list imports, exports, memories, globals,
types, functions, locals, instructions, element segments, and data segments.

# Hexana Claude Code Plugin

This package is the Claude Code wrapper for the standalone Hexana MCP runtime in `mcp/server`.

## What is bundled

- `.claude-plugin/plugin.json`: required Claude Code plugin manifest
- `.mcp.json`: MCP server configuration using `${CLAUDE_PLUGIN_ROOT}` for portable path resolution
- `bin/hexana-mcp`: thin launcher that delegates to the bundled standalone server
- `skills/wasm-inspector/`: bundled skill for WebAssembly inspection workflows

## Repo-local install flow

```bash
./gradlew :mcp:installRepoLocalClaudePlugin
```

That task installs the assembled plugin to `plugins/hexana/` with the bundled server runtime.

After running the task, point Claude Code at the plugin directory:

```bash
claude --plugin-dir plugins/hexana
```

## Assembly (for distribution)

```bash
./gradlew :mcp:assembleMcpPackaging
```

The assembled Claude plugin lands in `mcp/build/packaging/claude-plugin/` with the bundled server runtime. The `.mcp.json` uses `${CLAUDE_PLUGIN_ROOT}` so it resolves correctly when installed as a Claude Code plugin.

## Notes

- The `.mcp.json` uses Claude's native `${CLAUDE_PLUGIN_ROOT}` variable substitution, so no build-time path rewriting is needed.
- `bin/hexana-mcp` supports a `HEXANA_SERVER_BIN` environment variable override for development.
- The `wasm-inspector` skill guides Claude to prefer Hexana MCP tools over shell tooling for WebAssembly analysis.

# Hexana Claude Code Plugin

This package is the Claude Code wrapper for the standalone Hexana MCP runtime in `mcp/server`.

## What is bundled

- `.claude-plugin/plugin.json`: required Claude Code plugin manifest (version rendered at assembly time from `mcp/server/gradle.properties:mcpServerVersion`)
- `.mcp.json`: MCP server configuration using `${CLAUDE_PLUGIN_ROOT}` for portable path resolution
- `bin/hexana-mcp`: POSIX launcher that delegates to the bundled standalone server
- `bin/hexana-mcp.cmd`: Windows launcher equivalent
- `LICENSE.txt`: JetBrains plugin license agreement
- `THIRD-PARTY-NOTICES.md`: generated third-party dependency notices
- `skills/wasm-inspector/`: bundled skill for WebAssembly inspection workflows

## Install flows

### Official Anthropic marketplace (once listed)

```
/plugin install hexana
```

Once the Hexana plugin is accepted into the Anthropic marketplace, this is the recommended path for end users.

### Manual ZIP install

1. Download `hexana-claude-plugin-<version>.zip` from the GitHub Release for that version.
2. Unzip it somewhere stable, for example `~/claude-plugins/hexana/`.
3. Point Claude Code at the unzipped directory:

   ```
   claude --plugin-dir ~/claude-plugins/hexana
   ```

`.mcp.json` uses `${CLAUDE_PLUGIN_ROOT}`, so the plugin resolves correctly regardless of where it is unzipped.

### Repo-local development

```
./gradlew :mcp:installRepoLocalClaudePlugin
claude --plugin-dir plugins/hexana
```

That task installs the assembled plugin to `plugins/hexana/` with the bundled server runtime. The same path is also used by `installRepoLocalCodexPlugin`, so run whichever you need last.

## Assembly and distribution

```
./gradlew :mcp:assembleMcpPackaging
```

Produces:

- `mcp/build/packaging/claude-plugin/` — assembled, ready to `--plugin-dir` directly.
- `mcp/build/distributions/hexana-claude-plugin-<version>.zip` — distributable archive with executable bits preserved on `bin/hexana-mcp` and `server/bin/server`.

## Notes

- The `.mcp.json` uses Claude's native `${CLAUDE_PLUGIN_ROOT}` variable substitution, so no build-time path rewriting is needed.
- `bin/hexana-mcp` and `bin/hexana-mcp.cmd` both honour the `HEXANA_SERVER_BIN` environment variable for development.
- The `wasm-inspector` skill guides Claude to prefer Hexana MCP tools over shell tooling for WebAssembly analysis.
- Bundled server footprint is ~130 MB; the runtime is a self-contained Gradle `installDist` layout of the `mcp/server` module.

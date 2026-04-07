# MCP Packaging

This directory contains client-specific wrapper templates for the standalone runtime in `mcp/server`.

Source layouts:

- `claude-plugin/`
- `codex-plugin/`

Build self-contained wrapper outputs with:

```bash
./gradlew :mcp:assembleMcpPackaging
```

This produces:

```text
mcp/build/packaging/claude-plugin/
mcp/build/packaging/codex-plugin/
```

Each assembled wrapper includes its own bundled standalone runtime under `server/` and exposes `bin/hexana-mcp`.
Its generated `.mcp.json` points at that launcher with an absolute path, so the built artifact does not depend on
client working-directory behavior or client-specific root-expansion features.

The `.mcp.json` files checked into `mcp/packaging/*` are source templates only. They intentionally contain
assembly placeholders and are not meant to be consumed directly by Claude or Codex before
`./gradlew :mcp:assembleMcpPackaging` runs.

For ad hoc local testing, you can still override the launcher path explicitly:

```bash
HEXANA_SERVER_BIN=/absolute/path/to/server/bin/server mcp/packaging/claude-plugin/bin/hexana-mcp version
```

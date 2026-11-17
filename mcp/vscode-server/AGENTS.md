# AGENTS.md for `mcp/vscode-server`

This module is the VS Code-facing MCP server distribution. It exists so the VS Code extension can launch a separate
MCP process without depending on IntelliJ Platform runtime or lifecycle.

## Ownership Boundary

- Keep this module thin. The actual MCP runtime, transport implementation, generated tool registration, and query
  handlers stay in `mcp/server` and `mcp/core`.
- Do not add IntelliJ Platform APIs, VS Code extension-host APIs, or per-tool schema logic here.
- Preserve stdio as the local-first default path for VS Code packaging. stdout must remain MCP protocol-only.
- Keep `--transport stdio` / `--transport http` compatibility here as a VS Code artifact convenience; the shared
  standalone CLI still owns the canonical `stdio` and `http` subcommands.

## Testing

- Run `./gradlew :mcp:vscode-server:test` after changing argument normalization or the VS Code artifact entrypoint.
- Run `./gradlew :mcp:vscode-server:installDist` before packaging or smoke-testing the bundled VS Code server.

# AGENTS.md for `mcp/packaging`

This folder contains client-specific wrapper layouts for the standalone MCP runtime in `mcp/server`.

## Ownership Boundary

- Keep this folder packaging-only: manifests, `.mcp.json`, launcher scripts, and README files.
- Do not duplicate MCP runtime logic here. The executable source of truth stays in `mcp/server`.
- Wrapper scripts should prefer a bundled `server/bin/server` runtime inside the assembled package.
- `HEXANA_SERVER_BIN` may override the bundled runtime for local testing.
- The source `.mcp.json` files in this folder are templates; assembled outputs should rewrite them to an absolute
  `bin/hexana-mcp` launcher path through `./gradlew :mcp:assembleMcpPackaging`.
- Keep local-first defaults. The packaged wrappers should launch the standalone server in `stdio` mode unless a client
  manifest explicitly requires something else.

## Testing

- Run `./gradlew :mcp:assembleMcpPackaging` before smoke-testing assembled wrappers.
- Do not point clients at the source-tree `.mcp.json` templates; they intentionally contain assembly placeholders.
- Prefer smoke checks like `mcp/build/packaging/claude-plugin/bin/hexana-mcp version` over ad hoc script edits.

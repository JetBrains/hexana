# Hexana Core MCPB

This directory is the source template for the local Claude Desktop MCPB package.
Build it from the repository root with:

```bash
./gradlew :mcp:assembleClaudeDesktopMcpb
```

The assembled bundle is written to:

```text
mcp/build/distributions-native/hexana-core-<platform>.mcpb
```

The package uses Claude Desktop's required `allowed_directories` picker. The MCPB
entrypoint converts selected directories into Hexana allowed roots before
starting the standalone Hexana MCP server over stdio.

Local native overrides passed through `-PhexanaMcpbNativeExecutable` or
`-PhexanaMcpbNativeZip` must be built from the current branch/foundation. The
native executable is checked directly with `version` and
`doctor --allowed-root <directory>` during assembly.

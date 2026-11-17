# Hexana Core MCPB

This directory is the source template for the local Claude Desktop MCPB package.
Build it from the repository root with:

```bash
./gradlew :mcp:assembleClaudeDesktopMcpb
```

This task requires the official `mcpb` CLI on `PATH`; TeamCity and local
developer machines should install `@anthropic-ai/mcpb` before running it.

The assembled bundle is written to:

```text
mcp/build/distributions-native/hexana-core-<platform>.mcpb
```

The staged `manifest.json` is generated during assembly from this template and
the shared `mcp:tools-generator` catalog, so its tool metadata stays aligned
with the runtime tools exposed by `tools/list`.

The package uses Claude Desktop's required `allowed_directories` picker. The MCPB
entrypoint converts selected directories into Hexana allowed roots before
starting the standalone Hexana MCP server over stdio.

Local native overrides passed through `-PhexanaMcpbNativeExecutable` or
`-PhexanaMcpbNativeZip` must be built from the current branch/foundation. The
native executable is checked directly with `version` and
`doctor --allowed-root <directory>` during assembly.

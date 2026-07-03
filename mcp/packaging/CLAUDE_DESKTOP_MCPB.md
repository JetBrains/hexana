# Claude Desktop MCPB Quickstart

This page covers the local Claude Desktop MCPB prototype only. It is not a
public MCP Registry or marketplace publishing guide.

The package follows the currently checked MCPB docs: manifest version `0.3`,
`mcpb validate`, `mcpb pack`, and `mcpb info` are the official CLI checks, and
`user_config` supports a multi-directory picker whose selected values are
expanded into the server argument array.

## Build

From the repository root:

```bash
./gradlew :mcp:assembleClaudeDesktopMcpb
```

Output:

```text
mcp/build/packaging-native/claude-desktop-mcpb/
mcp/build/distributions-native/hexana-core-<platform>.mcpb
```

The bundle is platform-specific. It includes `native/<platform>/hexana-mcp` and
keeps the existing JVM fallback under `server/` because the implementation still
shares the native-enabled wrapper layout that validates that fallback.

## Validate

The Gradle task above runs local manifest, launcher, native `version`, native
`doctor`, missing-root, and archive-content checks.

If the official MCPB CLI is installed:

```bash
mcpb validate mcp/build/packaging-native/claude-desktop-mcpb
mcpb pack mcp/build/packaging-native/claude-desktop-mcpb mcp/build/distributions-native/hexana-core-<platform>.mcpb
mcpb info mcp/build/distributions-native/hexana-core-<platform>.mcpb
```

The same checks are available through Gradle:

```bash
./gradlew :mcp:mcpbValidateClaudeDesktopMcpb :mcp:mcpbPackClaudeDesktopMcpb :mcp:mcpbInfoClaudeDesktopMcpb
```

If `mcpb` is not on `PATH`, install it with:

```bash
npm install -g @anthropic-ai/mcpb
```

## Preflight

Before installing in Claude Desktop, run `doctor` against the directory that will
be selected in the MCPB installer:

```bash
mcp/build/packaging-native/claude-desktop-mcpb/bin/hexana-mcp doctor --allowed-root "$PWD/wasmParser/src/commonTest/resources"
```

Expected signal:

```text
Overall: OK
```

## Install In Claude Desktop

1. Build `mcp/build/distributions-native/hexana-core-<platform>.mcpb`.
2. Open that `.mcpb` file with Claude Desktop, or install it through Claude
   Desktop's local extension install UI.
3. When prompted for `Allowed Directories`, select one or more directories that
   contain the `.wasm` files Claude may inspect.
4. For the demo fixture, select `<repo>/wasmParser/src/commonTest/resources`.
5. Finish installation.
6. Restart Claude Desktop if the Hexana tools do not appear immediately.
7. Ask Claude to use Hexana on a file inside one selected directory.

The MCPB manifest passes `${user_config.allowed_directories}` as the only
argument. Claude Desktop expands the selected directories into argv values, and
the package wrapper exports them through `HEXANA_MCP_ALLOWED_ROOTS` before
starting `hexana-mcp stdio`.

Common log locations:

```text
macOS:   ~/Library/Logs/Claude/
Windows: %APPDATA%\Claude\logs\
```

Look for MCP or extension log files if the server does not appear.

## Demo Checklist

Capture the following evidence for reviewer sign-off:

- `./gradlew :mcp:assembleClaudeDesktopMcpb` passes.
- `doctor --allowed-root "$PWD/wasmParser/src/commonTest/resources"` prints
  `Overall: OK`.
- Claude Desktop lists Hexana MCP tools after installation.
- A `tools/list` proof exists. Acceptable proof is a Claude Desktop log excerpt,
  MCP inspector output, or screenshot showing Hexana tools such as
  `summarize_binary`, `list_exports`, or `list_imports`.
- The fixture path inside the selected directory can be analyzed.
- A path outside the selected directory is denied.

Prompt for a small fixture:

```text
Use Hexana to summarize this WebAssembly file:
<repo>/wasmParser/src/commonTest/resources/snippets/const.wasm
```

Expected high-level result when `<repo>/wasmParser/src/commonTest/resources`
was selected:

- Claude calls a Hexana MCP tool, preferably `summarize_binary`.
- The tool call succeeds.
- The response identifies the input as a WebAssembly module and includes compact
  structured module details such as format and section or entity counts.

Denied-path proof:

```text
Use Hexana to summarize this WebAssembly file:
<repo>/wasmParser/src/commonTest/resources/snippets/const.wasm
```

Run that prompt after installing the MCPB with a different allowed directory,
for example `<repo>/mcp`. The tool call must fail with an outside-allowed-roots
error instead of reading the file. The expected error text contains:

```text
outside allowed roots
```

## Manual Fallback Config

For developer debugging without MCPB installation, use Claude Desktop's manual
MCP server configuration. Add an entry like this to `claude_desktop_config.json`
after replacing the two absolute paths:

```json
{
  "mcpServers": {
    "hexana": {
      "command": "/absolute/path/to/Hexana-mcp-mcdb/mcp/build/packaging-native/claude-desktop-mcpb/bin/hexana-mcp",
      "args": [
        "stdio",
        "--allowed-root",
        "/absolute/path/to/allowed/project"
      ]
    }
  }
}
```

Run the same `doctor --allowed-root ...` command before debugging Claude
Desktop startup.

Typical manual config locations:

```text
macOS:   ~/Library/Application Support/Claude/claude_desktop_config.json
Windows: %APPDATA%\Claude\claude_desktop_config.json
```

## Known Limits

- Local Claude Desktop installation only.
- Public MCP Registry publishing is not implemented.
- Signing and update channels are not implemented.
- The artifact is platform-specific.
- Windows MCPB assembly is disabled until the package has a Windows-specific
  entrypoint.
- The package is native-first while this implementation still includes the
  existing JVM fallback.

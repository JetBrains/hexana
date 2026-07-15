# Run Script CLI

`run-script` runs a small Hexana JavaScript script over one loaded binary. It is available from the scripting CLI:

```bash
hexana-cli-script run-script <binary> --script-text <javascript> --format raw
```

The combined MCP launcher exposes the same command alongside MCP server commands:

```bash
hexana-mcp run-script <binary> --script-text <javascript> --format raw
```

## Local Fixture

Create a tiny binary fixture:

```bash
printf '\001\002\003' > sample.bin
```

Run inline script text and print raw script output:

```bash
hexana-cli-script run-script sample.bin \
  --script-text 'print(binary.bytes.size); print(binary.bytes.get(1));' \
  --format raw
```

Expected output:

```text
3
2
```

The script can also be read from a file:

```bash
printf 'print(binary.bytes.get(2));\n' > sample.hexana.js
hexana-cli-script run-script sample.bin --script sample.hexana.js --format raw
```

Use JSON output when callers need status, diagnostics, binary identity, source metadata, and captured output lines:

```bash
hexana-cli-script run-script sample.bin \
  --script-text 'print(binary.bytes.size);' \
  --format json
```

The script runtime exposes only the Hexana globals `binary` and `print(...)`. `binary.bytes` provides indexed access to
the loaded bytes, and `print(...)` appends text lines to the captured output.

## Path Safety

Path inputs are checked against the CLI allowed-root policy. Without an allowed-root restriction, local CLI path input is
allowed. When allowed roots are configured, every path input must be inside one of those roots.

Pass allowed roots explicitly:

```bash
mkdir -p fixture-root
printf '\001\002\003' > fixture-root/sample.bin
hexana-cli-script run-script fixture-root/sample.bin \
  --allowed-root fixture-root \
  --script-text 'print(binary.bytes.size);' \
  --format raw
```

`--allowed-root` entries are additive with `HEXANA_CLI_ALLOWED_ROOTS`. The server-side MCP launcher uses the same
`--allowed-root` flag and reads `HEXANA_MCP_ALLOWED_ROOTS`; other MCP request limits use separate `HEXANA_MCP_*`
variables.

## MCP Profile

The MCP tool backing this command is `run_binary_script`. It belongs to the dedicated `scripting` MCP tool profile. It is
not part of the `production` or `advanced` MCP profiles.

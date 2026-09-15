# Run Script CLI

`run-script` runs a small Hexana JavaScript script over one loaded binary. It is available from the scripting CLI:

```bash
hexana-cli-script run-script <binary> --script-text <javascript> --format raw
```

`hexana-mcp` does not expose this direct command. MCP clients can instead select the `scripting` tool profile and call
the protocol tool `run_binary_script`.

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

## Script API

The runtime exposes the same globals here, in the IntelliJ plugin, and in the VS Code extension. The surface is defined
once in `plugins-shared/src/commonMain/kotlin/org/jetbrains/hexana/shared/scripting/`, so a script written for one host
runs unchanged in the others — with one exception, `open(path)`, which needs local file access and therefore reports
that it is unavailable in the VS Code webview and the web app.

Output:

- `print(...values)` — appends a line, joining the arguments with a space.
- `table(...columns)` — starts a table and declares its column headers.
- `row(...cells)` — adds a row to the current table. Cells are escaped and short rows are padded.

A script tab renders these in the order the script produced them: text stays text, a `table(...)` plus its `row(...)`
calls becomes a table, and printing text again ends the table. So a script can narrate between tables. Over the CLI the
same output is plain lines, with tables tab-separated.

`binary` — the loaded binary, with the same members for every format:

| Member | Description |
| --- | --- |
| `format`, `formatName`, `name`, `size` | Identity. `format` is a stable id such as `wasm`, `elf`, `macho`, `pe`, `zip`. `name` is the file name without its directory. |
| `path`, `dir` | Where the file is, or `null` when the binary came from bytes rather than a file. `dir` has no trailing separator; join with `/` on any platform. |
| `bytes` | `at`, `slice`, `hex`, `ascii`, `u8`/`u16`/`u32`, `indexOf`, plus `size`/`lastIndex`/`indices`/`get`. |
| `structure` | Top-level regions — Wasm sections, ELF sections, Mach-O segments — as `{name, offset, size, tab}`. |
| `strings` | Extracted strings as `{value, offset, size, index, tag}`. |
| `sizeAnalysis` | Size breakdown tree, or `null` for formats without size profiling. |
| `tabs` / `tab(name)` | Every table the plugins render for the binary, read by name with the same column headers. |
| `raw` | Escape hatch: the format-specific provider. Members not listed above resolve against it too. |

`open(path)` loads a second binary from a local path and returns the same members as `binary`, so a script can compare
two files without a second API:

```bash
hexana-cli-script run-script new.wasm \
  --script-text 'const old = open("old.wasm"); print(binary.size - old.size);'
```

Combined with `binary.dir` it reaches a file next to the one being analyzed, without the script knowing where either
lives — the sidecar pattern, e.g. the linker map beside an ELF:

```bash
hexana-cli-script run-script build/app.elf \
  --script-text 'const map = open(binary.dir + "/app.map"); print(map.size);'
```

`binary.path` and `binary.dir` report the *resolved* location, so a relative `--binary ./app.elf` still yields a path a
later `open(...)` can build on. The format is detected from the file content, the path obeys the allowed-root policy
below, and one run may open at most 8 distinct binaries (opening the same path again is free and does not count). In the VS Code webview and the web app —
neither of which can read files synchronously — `open` reports that instead of doing anything.

For the full reference including signatures, see `vscode-plugin/hexana-scripting.d.ts` (curated) and
`vscode-plugin/hexana-scripting-raw.d.ts` (the format-specific types behind `binary.raw`). Runnable examples live in
`examples/scripting/`.

Apart from `open(path)`, the runtime does not expose host classes, file reads, process creation, thread creation, or
native access.

Example — the largest regions of a binary, as a table:

```bash
hexana-cli-script run-script sample.bin --script-text '
  table("Region", "Offset", "Size");
  for (const r of binary.structure.slice().sort((a, b) => b.size - a.size)) {
    row(r.name, r.offset, r.size);
  }'
```

## Path Safety

Path inputs are checked against the CLI allowed-root policy. Without an allowed-root restriction, local CLI path input is
allowed. When allowed roots are configured, every path input must be inside one of those roots. This covers the binary,
the script file, and every path a script passes to `open(...)` — a script cannot reach a file the command itself could
not have been given.

Pass allowed roots explicitly:

```bash
mkdir -p fixture-root
printf '\001\002\003' > fixture-root/sample.bin
hexana-cli-script run-script fixture-root/sample.bin \
  --allowed-root fixture-root \
  --script-text 'print(binary.bytes.size);' \
  --format raw
```

`--allowed-root` entries are additive with `HEXANA_CLI_ALLOWED_ROOTS`. MCP transports configure their independent
allowed-root policy with `hexana-mcp stdio|http --allowed-root` and `HEXANA_MCP_ALLOWED_ROOTS`.

## MCP Profile

The MCP tool backing this command is `run_binary_script`. It belongs to the dedicated `scripting` MCP tool profile. It is
not part of the `default` or `advanced` MCP profiles.

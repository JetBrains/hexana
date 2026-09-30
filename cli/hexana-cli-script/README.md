# Hexana CLI Script

`hexana-cli-script` is the isolated Hexana product for running a JavaScript inspection script over one Wasm or native
binary. It intentionally contains only `run-script`; use `hexana-cli` for summaries and Wasm-to-WAT conversion.

## Install and run

The JDK 21 fallback archive is `hexana-cli-script-0.4.0.zip`. Extract it and run
`hexana-cli-script/bin/hexana-cli-script`; the product root contains `bin/`, `lib/`, `LICENSE.txt`, `README.md`, and
`THIRD-PARTY-NOTICES.md`. This JVM fallback requires JDK 21.

Native archives are `hexana-cli-script-native-macos-arm64-0.4.0.zip` and
`hexana-cli-script-native-linux-x64-0.4.0.zip`. They contain `bin/hexana-cli-script`, the license, this README, and
the notices, without a JDK requirement. Use the JDK 21 archive on other platforms.

```sh
./hexana-cli-script/bin/hexana-cli-script version
./hexana-cli-script/bin/hexana-cli-script help run-script
./hexana-cli-script/bin/hexana-cli-script tools
```

`version` prints the product version, `help [command]` documents the installed surface, and `tools` lists its exact
tool catalog. MCP commands and standard analysis commands are not part of this product.

## Run a script

Provide exactly one binary source and exactly one script source:

```sh
hexana-cli-script run-script program.wasm --script inspect.js --format raw
hexana-cli-script run-script app.bin --script-text 'print(binary.bytes.size);' --format raw
cat module.wasm | hexana-cli-script run-script --stdin --script-text 'print(binary.format);'
hexana-cli-script run-script --binary-base64 "$(base64 < app.bin)" --script-text 'print(binary.bytes.size);'
```

The binary source is a positional path, `--stdin` (or positional `-`), or `--binary-base64` / `--bytes-base64`.
The script source is either `--script <path>` or `--script-text <text>`. `run-script` supports `raw` (the default) and
`json` formats. Results go to stdout unless `--output <path>` is supplied; it creates parent directories and
overwrites an existing file, leaving stdout empty. Exit status is `0` for success, `1` for script or analysis failure,
and `2` for invalid command-line usage.

## Sandbox and limits

Scripts run in a GraalJS sandbox with no host access, host class lookup, GraalJS I/O, process creation, thread
creation, or native access. The script receives the read-only `binary` API, the `print(...)`/`table(...)`/`row(...)`
output globals, and `open(path)`, which parses another local binary into the same read-only API and is bound by the same
allowed-root policy as the binary and script paths. Fixed script limits are a 3-second timeout, at most 1,000 printed
lines, and at most 8 distinct binaries opened per run. Default byte limits are 100 MiB for binary files and inline binary
input, 1,000,000 bytes for inline or file scripts, and 60,000 output bytes.

Use `--max-file-bytes`, `--max-inline-bytes`, `--max-text-file-bytes`, and `--max-output-bytes` (or their
`HEXANA_CLI_MAX_*` environment variables) to set positive byte limits. Paths are unrestricted unless
`--allowed-root <path>` or `HEXANA_CLI_ALLOWED_ROOTS` with platform path-separator, comma, or newline-separated roots
is set, which restricts both binary and script paths to those roots.
`--max-input-bytes` sets both binary file and inline byte limits; when combined with either individual limit,
the last occurrence for that limit wins. The shared parser also accepts `--max-items` as an alias for
`--max-candidate-count`, but `run-script` has no candidate list; use `--max-output-bytes` to bound script output.

## Telemetry

Telemetry is enabled by default. Its capture payload has exactly `api_key`, `event`, `distinct_id`, and `properties`;
the event is `cli_command_invoked` and the properties are exactly `product_id`, `command_name`, and `surface` (`cli`).
It excludes binary content, paths, script text, and output. Disable it with leading `--telemetry-enabled false` or
`HEXANA_CLI_TELEMETRY_ENABLED=false`. Set `--telemetry-host` / `--telemetry-key` or `HEXANA_CLI_POSTHOG_HOST` /
`HEXANA_CLI_POSTHOG_KEY` for a custom PostHog endpoint or key.

# Hexana CLI

`hexana-cli` is the standard Hexana command-line product for inspecting Wasm and native binaries. It intentionally
contains only `summarize-binary`, `summarize-module`, and `wasm-to-wat`; use `hexana-cli-script` for `run-script`.

## Install and run

Release ZIPs contain one product only. The JDK 21 fallback archive is
`hexana-cli-0.4.0.zip`; extract it and run `hexana-cli/bin/hexana-cli`. It contains `bin/`, `lib/`, `LICENSE.txt`,
`README.md`, and `THIRD-PARTY-NOTICES.md`. The JVM launcher requires JDK 21.

Native archives are available as `hexana-cli-native-macos-arm64-0.4.0.zip` and
`hexana-cli-native-linux-x64-0.4.0.zip`. They contain `bin/hexana-cli`, the license, this README, and the notices;
they do not need a JDK. On another platform, or when a native build is unsuitable, use the JDK 21 archive.

```sh
./hexana-cli/bin/hexana-cli version
./hexana-cli/bin/hexana-cli help
./hexana-cli/bin/hexana-cli tools
```

`version` prints the product version, `help [command]` shows command help, and `tools` prints the exact product tool
catalog and its formats. `hexana-cli` does not provide MCP transports or `run-script`.

## Commands and input

```sh
# A compact JSON summary of a Wasm or native binary.
hexana-cli summarize-binary program.wasm

# A Wasm module summary (pretty output is the default).
hexana-cli summarize-module module.wasm --format pretty

# Convert a Wasm module to text and write it to a file.
hexana-cli wasm-to-wat module.wasm --format raw --output module.wat
```

Every command accepts exactly one source: a positional path, `--stdin` (or positional `-`), or its base64 option.
`summarize-binary` accepts `--binary-base64` and the compatibility alias `--bytes-base64`; Wasm commands accept
`--wasm-base64`.

```sh
cat module.wasm | hexana-cli summarize-module --stdin --format json
hexana-cli wasm-to-wat --wasm-base64 "$(base64 < module.wasm)" --format raw
```

Formats are command-specific: `summarize-binary` supports `json`; `summarize-module` supports `json` and `pretty`;
and `wasm-to-wat` supports `json` and `raw`. Results go to stdout unless `--output <path>` is given. `--output`
creates missing parent directories and overwrites an existing file; no payload is then printed to stdout.

Exit status is `0` for success, `1` for analysis or runtime failure, and `2` for invalid command-line usage.

## Access and limits

Paths are unrestricted by default. Supplying one or more `--allowed-root <path>` values (or
`HEXANA_CLI_ALLOWED_ROOTS` with platform path-separator, comma, or newline-separated roots) restricts input paths to
those roots. Defaults are 100 MiB each for file and inline binary
input, 1,000,000 bytes for text files, 60,000 output bytes, 500 candidates, 8 scan roots, and scan depth 16.

Use the matching `--max-file-bytes`, `--max-inline-bytes`, `--max-text-file-bytes`, `--max-output-bytes`,
`--max-candidate-count`, `--max-scan-roots`, and `--max-scan-depth` options, or the `HEXANA_CLI_MAX_*` environment
variables shown by command help, to lower or raise those positive limits.

## Telemetry

Telemetry is enabled by default. Its capture payload has exactly `api_key`, `event`, `distinct_id`, and `properties`;
the event is `cli_command_invoked` and the properties are exactly `product_id`, `command_name`, and `surface` (`cli`).
It does not send the input path, input bytes, script text, or command output. Disable it with the leading global option
`--telemetry-enabled false` or `HEXANA_CLI_TELEMETRY_ENABLED=false`. Advanced deployments can set the PostHog endpoint
and key with `--telemetry-host` / `--telemetry-key` or `HEXANA_CLI_POSTHOG_HOST` / `HEXANA_CLI_POSTHOG_KEY`.

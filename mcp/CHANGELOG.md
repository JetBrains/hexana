# Hexana MCP Changelog

## Unreleased

### Added
- `hexana-mcp summarize-binary <binary> --format json` can now summarize Wasm and native binaries as a public CLI command, returning JSON-only compact identity, counts, and warnings without raw bytes.
- Executable public CLI commands can now read input from a file path, `-`, `--stdin`, auto-detected piped stdin, or explicit base64 options such as `--binary-base64`, `--wasm-base64`, and `--bytes-base64`.

### Changed
- Standalone MCP now defaults generated tool registration to the production profile; callers that need hidden or advanced tools must request `HexanaMcpToolProfile.ADVANCED`.
- MCP tool schemas now use schema version 2 and advertise bounded list inputs such as `indices.maxItems` for clients that validate requests before calling tools.

## 0.4.0 - 2026-07-06

### Added
- Claude Desktop MCPB packaging can build platform-specific `hexana-core-<platform>.mcpb` bundles with the native Hexana MCP runtime, generated tool metadata, local validation, and smoke checks.
- Standalone MCP resource limits can be configured with CLI flags or environment variables, and oversized inputs now return deterministic resource-limit errors.
- MCP downloads publication now produces a root `releases.json` index for latest-version discovery in addition to the versioned release manifest.
- Split CLI launchers are packaged as `hexana-cli` for `summarize-binary`, `summarize-module`, and `wasm-to-wat`, plus `hexana-cli-script` for `run-script`; both use the shared MCP version, `tools`, `version`, `help`, and `HEXANA_CLI_*` resource, allowed-root, and telemetry environment variables.

### Changed
- The standalone MCP CLI accepts repeated `--allowed-root` arguments so MCPB directory-picker selections can be forwarded directly to the server.

## 0.3.1 - 2026-07-02

### Fixed
- Public Claude Code and Codex marketplace metadata now bundles the macOS arm64 and Linux x64 native MCP executables so marketplace installs use the native launcher on supported platforms.
- Release validation now rejects mismatched bundled-native marketplace metadata before public publication.
- Packaged Claude Code and Codex skill guidance now references only MCP tools available in the default release profile.

## 0.3.0 - 2026-07-01

### Added
- Binary inspection tools can summarize Wasm and native binaries, query binary records, and list or search functions.
- Native MCP release artifacts cover macOS arm64 and Linux x64, with Claude Code and Codex plugin wrappers preferring the bundled native executable on those platforms.

### Changed
- Standalone MCP release metadata now targets version 0.3.0 for the current server line.
- Windows users continue to use the JVM fallback package while native Windows binaries remain disabled for this release.

## 0.2.0 - 2026-06-23

### Added
- Native MCP release artifacts for macOS arm64 and Linux x64 are part of the release flow.
- Claude Code and Codex plugin ZIPs use bundled native executables on supported platforms and keep the JVM runtime as fallback.
- Release automation validates native artifact policy before publishing GitHub Release assets and public marketplace metadata.

### Changed
- Codex and Claude release automation is native-first by default: release publishing expects the native matrix ZIPs to be available before public publication.

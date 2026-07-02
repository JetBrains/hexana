# Hexana MCP Changelog

## Unreleased

### Added
- Standalone MCP resource limits can be configured with CLI flags or environment variables, and oversized inputs now return deterministic resource-limit errors.

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

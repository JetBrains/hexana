# Hexana MCP Changelog

## Unreleased

## 0.2.0 - 2026-06-23

### Added
- Native MCP release artifacts for macOS arm64 and Linux x64 are part of the release flow.
- Claude Code and Codex plugin ZIPs use bundled native executables on supported platforms and keep the JVM runtime as fallback.
- Release automation validates native artifact policy before publishing GitHub Release assets and public marketplace metadata.

### Changed
- Codex and Claude release automation is native-first by default: release publishing expects the native matrix ZIPs to be available before public publication.


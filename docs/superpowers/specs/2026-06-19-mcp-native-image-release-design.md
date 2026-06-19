# Hexana MCP Native Image Release Design

## Context

The standalone Hexana MCP server ships as a JVM `installDist` runtime under
`mcp/server`. Native Image support adds platform-native executables for lower
startup cost while keeping the JVM runtime as the compatibility fallback for the
first native-enabled release path.

The repository-level instructions mention a durable `kb/wiki/` store, but this
worktree does not contain that directory. This checked-in design note is the
durable repo-local record for the native MCP packaging decisions.

## Decisions

- Native executables are built from `mcp/server` with
  `org.jetbrains.hexana.mcp.server.MainKt` as the entrypoint.
- The executable name is `hexana-mcp` on Unix platforms and `hexana-mcp.exe` on
  Windows.
- The supported native release matrix is:
  - `macos-arm64`
  - `linux-x64`
  - `windows-x64`
- Native-enabled Claude and Codex packages include both:
  - `native/<platform>/hexana-mcp`, or `hexana-mcp.exe` on Windows
  - `server/`, the JVM standalone runtime fallback
- Wrapper launchers resolve the server in this order:
  - `HEXANA_SERVER_BIN`, for explicit local testing overrides
  - a bundled native executable matching the current platform
  - `server/bin/server`, or `server/bin/server.bat` on Windows
- Unsupported platforms are supported through the bundled JVM runtime, not by
  cross-running a native executable from another platform.
- The first native release gate is stdio-first. Every native matrix leg must pass
  `hexana-mcp version` and native stdio MCP smoke before its artifacts are
  publishable.
- Native HTTP validation is available as a separate smoke task. It is not part of
  `:mcp:assembleNativeMcpReleaseArtifacts` until the platform matrix has rebuilt
  and passed it consistently.
- JVM MCP analytics remain enabled by default for `stdio` and `http`.
- GraalVM Native Image MCP analytics are disabled by default because PostHog
  networking and shutdown-hook behavior adds startup/runtime surface that can
  destabilize native smoke reliability. Native users can opt in explicitly with
  `--telemetry-enabled true` or `HEXANA_MCP_TELEMETRY_ENABLED=true`.
- Public Claude/Codex release packages continue to use the R8-obfuscated JVM
  runtime layout. Native artifacts are additive and are collected from platform
  CI matrix outputs by the release script.

## Release Artifacts

Each native CI matrix leg runs:

```bash
./gradlew :mcp:assembleNativeMcpReleaseArtifacts
```

The task produces current-host artifacts in `mcp/build/distributions-native/`:

- `hexana-mcp-native-<platform>-<version>.zip`
- `hexana-claude-plugin-<platform>-<version>.zip`
- `hexana-codex-plugin-<platform>-<version>.zip`

The release job downloads every matrix leg's native artifacts into one directory
and runs `mcp/packaging/release-codex-claude-plugins.sh` with
`INCLUDE_NATIVE_ARTIFACTS=true`. The script requires all supported platforms by
default and rejects missing native executable, Claude plugin, or Codex plugin
ZIPs.

## Known Limitations

- Native Image builds require a GraalVM distribution with `native-image`
  installed. JBR and ordinary JDKs can run JVM packaging and tests, but cannot
  rebuild native executables.
- Native release artifacts are built per host platform. The Gradle flow does not
  cross-compile native executables.
- HTTP mode exists in the native executable, and `:mcp:server:nativeMcpHttpSmoke`
  exists for validation, but the release gate remains version plus stdio until
  HTTP is proven on the full native matrix.
- Native telemetry is default-off. Explicit opt-in is supported, but startup and
  MCP protocol smoke gates run with the default native telemetry behavior.

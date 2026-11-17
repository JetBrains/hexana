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
  `hexana-mcp version` with exact `mcp/server/gradle.properties:mcpServerVersion`
  output and native stdio MCP smoke before its artifacts are publishable. A
  fallback `hexana-mcp dev` output is a release-gate failure.
- Native HTTP validation is part of `:mcp:assembleNativeMcpReleaseArtifacts`.
  The aggregate also runs the Kotlin/JUnit native split-CLI contract against
  both product executables; existing imperative archive and behavior smokes
  remain during the rollout.
- MCP analytics remain enabled by default for JVM and GraalVM Native Image
  `stdio` and `http`; users can opt out explicitly with
  `--telemetry-enabled false` or `HEXANA_MCP_TELEMETRY_ENABLED=false`.
- PostHog networking and shutdown-hook behavior adds startup/runtime surface
  that can destabilize native smoke reliability, so native release smokes must
  keep covering the default analytics-enabled command line.
- Public Claude/Codex packages continue to include the R8-obfuscated JVM
  runtime layout as a fallback. Native artifacts are the release path and are
  collected from platform CI matrix outputs by the release script.
- Windows users are supported through the JVM fallback rather than a
  required Windows native artifact.
- `mcp/CHANGELOG.md` is the MCP-specific changelog source. Release automation
  extracts the section matching `mcp/server/gradle.properties:mcpServerVersion`
  and uses it as the GitHub Release notes body after the public text guard has
  accepted the generated notes.

## Release Artifacts

Each native CI matrix leg runs:

```bash
./gradlew :mcp:assembleNativeMcpReleaseArtifacts
```

The task produces current-host artifacts in `mcp/build/distributions-native/`:

- `hexana-mcp-native-<platform>-<version>.zip`
- `hexana-cli-native-<platform>-<version>.zip`
- `hexana-cli-script-native-<platform>-<version>.zip`
- `hexana-claude-plugin-<platform>-<version>.zip`
- `hexana-codex-plugin-<platform>-<version>.zip`

The release job downloads every matrix leg's native artifacts into one directory
and runs `mcp/packaging/release-codex-claude-plugins.sh` with
`INCLUDE_NATIVE_ARTIFACTS=true`, which is the script default. The script requires
all supported platforms by default and rejects missing MCP native, split CLI
native, Claude plugin, or Codex plugin ZIPs before publishing.

Before any public mutation, the release job also requires a non-empty
`mcp/CHANGELOG.md` entry for the release version. The generated
`release-notes.md` is derived from that entry and scanned for internal hosts,
local paths, direct URLs, CI/token markers, and private key patterns.

## Known Limitations

- Native Image builds require a GraalVM distribution with `native-image`
  installed. JBR and ordinary JDKs can run JVM packaging and tests, but cannot
  rebuild native executables.
- Native release artifacts are built per host platform. The Gradle flow does not
  cross-compile native executables.
- Native HTTP mode is a required current-host aggregate smoke. Full release
  readiness still requires evidence from both supported matrix legs.
- Native MCP telemetry is enabled by default, matching the JVM runtime; users
  can opt out explicitly. Split CLI contracts disable telemetry by default and
  separately prove local fake-endpoint reporting for both products.

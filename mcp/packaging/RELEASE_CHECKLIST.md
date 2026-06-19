# MCP Release Checklist

Use this checklist for Claude and Codex MCP wrapper releases.

## JVM Package Gates

- Build and smoke-test pre-R8 packages:

  ```bash
  ./gradlew :mcp:assembleMcpPackagingPlain :mcp:server:plainMcpPackagingSmoke
  ```

- Build and smoke-test R8-obfuscated release packages:

  ```bash
  ./gradlew :mcp:assembleMcpPackaging :mcp:server:obfuscatedMcpPackagingSmoke
  ```

- If `mcp/packaging/release-codex-claude-plugins.sh` changed, run:

  ```bash
  bash -n mcp/packaging/release-codex-claude-plugins.sh
  ```

- Run the GitHub-facing text guard when release text or script validation changes:

  ```bash
  PUBLIC_GITHUB_TEXT_GUARD_SELF_TEST=true ./mcp/packaging/release-codex-claude-plugins.sh
  ```

## Native Matrix Gates

Run each native matrix leg on a host with a GraalVM distribution that includes
`native-image`:

| Platform tag | Host requirement |
|--------------|------------------|
| `macos-arm64` | macOS on arm64 |
| `linux-x64` | Linux on x86_64 |
| `windows-x64` | Windows on x86_64 |

Each leg must run:

```bash
./gradlew :mcp:assembleNativeMcpReleaseArtifacts
```

That task builds the current-host native executable, verifies `hexana-mcp
version`, runs the native stdio MCP smoke test, and writes native standalone,
Claude plugin, and Codex plugin ZIPs under `mcp/build/distributions-native/`.

Run the optional native HTTP smoke when qualifying HTTP support on a platform:

```bash
./gradlew :mcp:server:nativeMcpHttpSmoke
```

HTTP smoke is intentionally separate from the native release artifact gate until
it is proven across the matrix.

## Release Script Inputs

Before invoking `mcp/packaging/release-codex-claude-plugins.sh` for a
native-enabled release:

- Download the native matrix artifacts into `NATIVE_RELEASE_ARTIFACT_DIR`, or use
  the default `mcp/build/distributions-native` if the release job assembled them
  in the same checkout.
- Set `INCLUDE_NATIVE_ARTIFACTS=true`.
- Keep `NATIVE_RELEASE_PLATFORMS` at the default
  `macos-arm64 linux-x64 windows-x64` unless the supported matrix is intentionally
  changing.
- Ensure `PLUGIN_VERSION` matches `mcp/server/gradle.properties:mcpServerVersion`
  unless the release job intentionally overrides it.
- Keep the JVM release gates visible in the same build chain. Set
  `SKIP_RELEASE_BUILD_AND_SMOKE=true` only after those gates already passed in
  the same checkout.

## Artifact Checks

- Confirm every native-enabled wrapper contains `native/<platform>/` for its
  platform and `server/` as the JVM fallback.
- Confirm unsupported platforms launch through the JVM fallback instead of a
  mismatched native executable.
- Confirm release ZIP security checks pass before uploading GitHub Release
  assets.
- Confirm public marketplace bundle validation passes before opening or updating
  the public marketplace pull request.

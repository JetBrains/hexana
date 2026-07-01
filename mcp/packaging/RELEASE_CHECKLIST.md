# MCP Release Checklist

Use this checklist for Claude and Codex MCP wrapper releases.

## Native Matrix Gates

Run each native matrix leg on a host with a GraalVM distribution that includes
`native-image`:

| Platform tag | Host requirement |
|--------------|------------------|
| `macos-arm64` | macOS on arm64 |
| `linux-x64` | Linux on x86_64 |

Windows users are supported by the bundled JVM fallback for the 0.3.0 release;
Windows native ZIPs are not required release artifacts in this matrix.

Each leg must run:

```bash
./gradlew :mcp:assembleNativeMcpReleaseArtifacts
```

That task builds the current-host native executable, verifies `hexana-mcp
version` prints exactly `hexana-mcp <mcpServerVersion>` from
`mcp/server/gradle.properties`, runs the native stdio MCP smoke test, and writes
native standalone, Claude plugin, and Codex plugin ZIPs under
`mcp/build/distributions-native/`. A native executable that falls back to
`hexana-mcp dev` must fail this gate.

Run the optional native HTTP smoke when qualifying HTTP support on a platform:

```bash
./gradlew :mcp:server:nativeMcpHttpSmoke
```

HTTP smoke is intentionally separate from the native release artifact gate until
it is proven across the matrix.

## JVM Fallback Compatibility Checks

- Update `mcp/CHANGELOG.md` before release publishing. The release script
  extracts the `mcp/server/gradle.properties:mcpServerVersion` section and
  fails before public publication when the matching entry is missing or empty.

- Build and smoke-test pre-R8 JVM fallback packages:

  ```bash
  ./gradlew :mcp:assembleMcpPackagingPlain :mcp:server:plainMcpPackagingSmoke
  ```

- Build and smoke-test R8-obfuscated JVM fallback packages:

  ```bash
  ./gradlew :mcp:assembleMcpPackaging :mcp:server:obfuscatedMcpPackagingSmoke
  ```

These checks keep the bundled `server/` fallback honest. They are not sufficient
release readiness without the native matrix artifacts above.

- If `mcp/packaging/release-codex-claude-plugins.sh` changed, run:

  ```bash
  bash -n mcp/packaging/release-codex-claude-plugins.sh
  ```

- Run the GitHub-facing text guard when release text or script validation changes:

  ```bash
  PUBLIC_GITHUB_TEXT_GUARD_SELF_TEST=true ./mcp/packaging/release-codex-claude-plugins.sh
  ```

- Run the changelog-driven release-notes self-test when changelog validation or
  release-note extraction changes:

  ```bash
  MCP_CHANGELOG_RELEASE_NOTES_SELF_TEST=true ./mcp/packaging/release-codex-claude-plugins.sh
  ```

## Release Script Inputs

Before invoking `mcp/packaging/release-codex-claude-plugins.sh` for a
release:

- Download the native matrix artifacts into `NATIVE_RELEASE_ARTIFACT_DIR`, or use
  the default `mcp/build/distributions-native` if the release job assembled them
  in the same checkout.
- Leave `INCLUDE_NATIVE_ARTIFACTS=true` at its default. Setting it to `false`
  is only for explicit JVM fallback diagnostics and must not be used for
  release publishing.
- Keep `NATIVE_RELEASE_PLATFORMS` at the default
  `macos-arm64 linux-x64` unless the supported matrix is intentionally
  changing.
- The release script fails before publishing when public `release.lock.json`
  advertises GitHub Release-only native artifacts but the matching ZIPs were not
  collected into the release directory.
- Ensure `PLUGIN_VERSION` matches `mcp/server/gradle.properties:mcpServerVersion`
  unless the release job intentionally overrides it.
- Ensure `mcp/CHANGELOG.md` contains the matching `## <version>` entry with
  public-safe notes. The generated GitHub Release notes are scanned by the
  public text guard before any public mutation.
- Keep the JVM fallback compatibility checks visible in the same build chain.
  Set `SKIP_RELEASE_BUILD_AND_SMOKE=true` only after those checks and all native
  matrix gates already passed in the same checkout/build chain.

## Artifact Checks

- Confirm every native-enabled wrapper contains `native/<platform>/` for its
  platform and `server/` as the JVM fallback.
- Confirm unsupported platforms launch through the JVM fallback instead of a
  mismatched native executable.
- Confirm release ZIP security checks pass before uploading GitHub Release
  assets.
- Confirm public marketplace bundle validation passes before opening or updating
  the public marketplace pull request.
- Confirm downloads.jetbrains.com publication consumes the already verified
  native matrix ZIPs, not a local rebuild.
- Confirm `publish-downloads-release-bundle.sh` generated one `.sha256` file per
  public ZIP and `hexana-mcp-release-<version>.json` from the copied files.
- Confirm the downloads upload target is the versioned prefix
  `s3://downloads-bucket.example.invalid/resources/hexana/mcp/<version>/`.

If downloads publication fails after the bundle directory was generated, rerun
the same TeamCity publication build. The script regenerates checksums and the
manifest from the same downloaded ZIPs and overwrites the same versioned prefix
in one recursive upload action.

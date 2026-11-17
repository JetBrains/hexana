# MCP Release Checklist

Use this checklist for Claude and Codex MCP wrapper releases.

## Native Matrix Gates

Run each native matrix leg on a host with GraalVM 25 or newer that includes
`native-image`. The repository JVM baseline and JVM fallback packages remain on
JDK 21; native packaging requires GraalVM 25 because `wasm-language` 25.0.0 uses
`VectorAPISupport`.

| Platform tag | Host requirement |
|--------------|------------------|
| `macos-arm64` | macOS on arm64 |
| `linux-x64` | Linux on x86_64 |

Windows users are supported by the bundled JVM fallback for this release;
Windows native ZIPs are not required release artifacts in this matrix.

Each leg must run:

```bash
./gradlew :mcp:assembleNativeMcpReleaseArtifacts
```

That task builds the current-host native executable, verifies `hexana-mcp
version` prints exactly `hexana-mcp <mcpServerVersion>` from
`mcp/server/gradle.properties`, runs the native stdio and HTTP MCP smoke tests,
and writes native standalone, Claude plugin, and Codex plugin ZIPs under
`mcp/build/distributions-native/`. It also verifies the current-host
`hexana-cli-native-<platform>-<version>.zip` and
`hexana-cli-script-native-<platform>-<version>.zip` archives and their
`version` launchers. A native executable that falls back to `hexana-mcp dev`
must fail this gate.

## JVM Fallback Compatibility Checks

These checks exercise the ordinary JVM fallback packages. They require JDK 21,
not GraalVM 25 `native-image`.

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

- Run the native publication policy self-test when native artifact or public
  marketplace bundle validation changes:

  ```bash
  RELEASE_NATIVE_ARTIFACT_POLICY_SELF_TEST=true ./mcp/packaging/release-codex-claude-plugins.sh
  ```

- Run the changelog-driven release-notes self-test when changelog validation or
  release-note extraction changes:

  ```bash
  MCP_CHANGELOG_RELEASE_NOTES_SELF_TEST=true ./mcp/packaging/release-codex-claude-plugins.sh
  ```

- Run the publication-mode guard self-test when production/test-nightly routing
  changes:

  ```bash
  PUBLICATION_MODE_GUARD_SELF_TEST=true ./mcp/packaging/release-codex-claude-plugins.sh
  ```

## Release Script Inputs

Before invoking `mcp/packaging/release-codex-claude-plugins.sh` for a
release:

- Download the native matrix artifacts into `NATIVE_RELEASE_ARTIFACT_DIR`, or use
  the default `mcp/build/distributions-native` if the release job assembled them
  in the same checkout.
- Include the split CLI native ZIPs for every supported platform:
  `hexana-cli-native-<platform>-<version>.zip` and
  `hexana-cli-script-native-<platform>-<version>.zip`.
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
- Keep `PUBLICATION_MODE=production` for the canonical public release. Use
  `PUBLICATION_MODE=test-nightly` only with a non-production GitHub repository;
  that mode refuses `AUTO_MERGE=true` and defaults the tag to a nightly tag when
  `RELEASE_TAG` is empty.
- Ensure `mcp/CHANGELOG.md` contains the matching `## <version>` entry with
  public-safe notes. The generated GitHub Release notes are scanned by the
  public text guard before any public mutation.
- Keep the JVM fallback compatibility checks visible in the same build chain.
  Set `SKIP_RELEASE_BUILD_AND_SMOKE=true` only after those checks and all native
  matrix gates already passed in the same checkout/build chain.

## Artifact Checks

- Confirm every native-enabled wrapper contains `native/<platform>/` for its
  platform and `server/` as the JVM fallback.
- If the public marketplace PR tree bundles native executables, confirm every
  `plugins/hexana/native/<platform>/hexana-mcp` file has a matching
  `nativeArtifacts.platforms` entry in `plugins/hexana/release.lock.json`, and
  that no undeclared native platform files are present.
- Confirm unsupported platforms launch through the JVM fallback instead of a
  mismatched native executable.
- Confirm release ZIP security checks pass before uploading GitHub Release
  assets.
- Confirm public marketplace bundle validation passes before opening or updating
  the public marketplace pull request.
- Confirm downloads.jetbrains.com publication consumes the already verified
  native matrix ZIPs, not a local rebuild.
- Confirm the MCP bundle contains only native MCP, Claude, and Codex ZIPs, one
  `.sha256` per ZIP, and `hexana-mcp-release-<version>.json`.
- Confirm the CLI bundle contains only `hexana-cli-native-*` and
  `hexana-cli-script-native-*` ZIPs, one `.sha256` per ZIP,
  `hexana-cli-release-<version>.json`, and the rendered `README.md` inside the
  version directory.
- Confirm the independent root indexes were generated at
  `mcp/out/teamcity/downloads-index/releases.json` and
  `mcp/out/teamcity/cli-downloads-index/releases.json`, and each current entry
  points to its own product manifest.
- Confirm the versioned bundle upload targets are
  `s3://$HEXANA_DOWNLOADS_S3_BUCKET/resources/hexana/mcp/<version>/` and
  `s3://$HEXANA_DOWNLOADS_S3_BUCKET/resources/hexana/cli/<version>/`.
- Confirm the root index upload targets are
  `s3://$HEXANA_DOWNLOADS_S3_BUCKET/resources/hexana/mcp/releases.json` and
  `s3://$HEXANA_DOWNLOADS_S3_BUCKET/resources/hexana/cli/releases.json`.

If downloads publication fails after bundle generation, rerun the same
TeamCity publication build. The script regenerates both products from the same
downloaded ZIPs, independently merges both existing indexes, overwrites the
same versioned prefixes, and uploads each root index separately.

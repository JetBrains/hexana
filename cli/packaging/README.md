# CLI downloads release bundle

`publish-cli-downloads-release-bundle.sh` consumes already verified native ZIPs and one producer identity JSON per target. The checked-in `cli/metadata/src/main/gen/cli-release-artifacts.json` is a versioned descriptor generated from `CliProductSpec` declarations. It lists product IDs, native artifact kinds, launchers, platform/architecture targets, identity file names, and versioned archive name templates. `CLI_RELEASE_DESCRIPTOR` can select a descriptor fixture for local validation.

The current descriptor expects exactly six native archives: `hexana-cli`,
`hexana-cli-script`, and `hexana-wasm-tools` on `macos-arm64` and `linux-x64`.
For version `<version>`, the complete input is:

| Product | macOS arm64 | Linux x64 | Launcher / artifact kind |
| --- | --- | --- | --- |
| `hexana-cli` | `hexana-cli-native-macos-arm64-<version>.zip` | `hexana-cli-native-linux-x64-<version>.zip` | `bin/hexana-cli` / `standard-cli-native` |
| `hexana-cli-script` | `hexana-cli-script-native-macos-arm64-<version>.zip` | `hexana-cli-script-native-linux-x64-<version>.zip` | `bin/hexana-cli-script` / `script-cli-native` |
| `hexana-wasm-tools` | `hexana-wasm-tools-native-macos-arm64-<version>.zip` | `hexana-wasm-tools-native-linux-x64-<version>.zip` | `bin/hexana-wasm-tools` / `hexana-wasm-tools-native` |

Include `macos-arm64-identity.json` and `linux-x64-identity.json`, each naming
and hashing all three archives from its producer. Identities must agree on the
source revision and shared `hexanaToolsVersion`. Every ZIP contains exactly
its executable launcher, `README.md`, `LICENSE.txt`, and
`THIRD-PARTY-NOTICES.md`. Adding another native-enabled product declaration
adds one expected archive per target; matching native Gradle tasks must supply it.
The validator rejects missing, extra, duplicate, wrong-identity, wrong-target,
wrong-launcher, non-executable, and digest-mismatched inputs before bundle creation.

The publisher derives manifest, ledger, checksums, download table, and release
index membership from the validated descriptor. Existing version, target,
destination, immutable ledger, and retry policies remain in force.

Verification commands:

```bash
./gradlew verifyCliReleasePackaging
bash -n cli/packaging/publish-cli-downloads-release-bundle.sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s cli/packaging -p 'test_*.py' -v
CLI_DOWNLOADS_BUNDLE_SELF_TEST=true bash cli/packaging/publish-cli-downloads-release-bundle.sh
./gradlew :cli:metadata:verifyHexanaCliMetadataUpToDate :mcp:verifyNativeCliReleaseDescriptor
./gradlew :mcp:tools-generator:test --tests '*CliReleaseArtifactDescriptorTest'
./gradlew :mcp:assembleNativeCliReleaseArchives :mcp:verifyNativeCliReleaseArchives
```

`verifyCliReleasePackaging` runs the Python validator suite and fake-storage
publisher self-test in the TeamCity CLI and Server/CLI Patronus lanes. The
remaining commands support focused local checks. Native archive assembly and
execution require GraalVM 25+ on a supported host. The native verification
extracts each ZIP, checks its launcher, license and notices, runs its exact
command inventory and shared version, and compares native/JVM output and exits.
The standard inventory includes `inspect-binary`; the script inventory includes
`test`, matching their existing installed-product contracts.
For Wasm tools it exercises core and component reports in JSON and pretty plus usage
and unreadable-input rejection. Notices cover the union of all three CLI runtime
dependency graphs and the MCP server.

The self-test uses fake local S3 storage and does not publish to the network.
It proves the six-archive manifest/ledger/index and checksum inventory, rejects
the old four-archive input, temporarily removes a required Wasm archive and
substitutes a wrong identity/launcher, then restores the exact set before retry.
It also proves interrupted uploads, immutable bytes, stale index rejection,
prerelease handling, regenerated-metadata retry, and temporary-directory cleanup.
Python fixtures cover both Wasm targets, duplicate identities, layout/license/notice
failures, and a fourth synthetic product with a distinct executable name to
preserve descriptor extensibility and product/launcher identity separation.
Production upload remains gated by the existing `PUBLISH_DOWNLOADS` flag and exact destination checks.

# CLI downloads release bundle

`publish-cli-downloads-release-bundle.sh` consumes already verified native ZIPs and one producer identity JSON per target. The checked-in `cli/metadata/src/main/gen/cli-release-artifacts.json` is a versioned descriptor generated from `CliProductSpec` declarations. It lists product IDs, native artifact kinds, launchers, platform/architecture targets, identity file names, and versioned archive name templates. `CLI_RELEASE_DESCRIPTOR` can select a descriptor fixture for local validation.

For the current release, the descriptor expects `hexana-cli` and `hexana-cli-script` on `macos-arm64` and `linux-x64`. Adding a product declaration adds one expected archive per declared target; the native Gradle assembly/verification tasks must also supply that product before a real release. The publisher rejects missing or extra files, duplicate or incomplete descriptor matrices, mismatched identity inventories or digests, and unsafe ZIP layouts or non-executable launchers. It preserves the immutable ledger, manifest, release index, retry and destination rules. The native Gradle verification still checks host execution and JVM/native compatibility.

Verification commands:

```bash
./gradlew verifyCliReleasePackaging
bash -n cli/packaging/publish-cli-downloads-release-bundle.sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s cli/packaging -p 'test_*.py' -v
CLI_DOWNLOADS_BUNDLE_SELF_TEST=true bash cli/packaging/publish-cli-downloads-release-bundle.sh
./gradlew :cli:metadata:verifyHexanaCliMetadataUpToDate :mcp:verifyNativeCliReleaseDescriptor
```

`verifyCliReleasePackaging` runs the Python validator suite and fake-storage
publisher self-test in the TeamCity CLI and Server/CLI Patronus lanes. The
remaining commands support focused local checks.

The self-test uses fake local S3 storage and does not publish to the network. Production upload remains gated by the existing `PUBLISH_DOWNLOADS` flag and exact destination checks.

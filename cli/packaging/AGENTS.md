# AGENTS.md for `cli/packaging`

`publish-cli-downloads-release-bundle.sh` is the CLI downloads publisher. It
accepts the descriptor-declared verified native archives and platform identity
files, then owns the immutable release ledger, manifest, checksums, README, and
CLI release index. The generated descriptor is
`cli/metadata/src/main/gen/cli-release-artifacts.json`; see `README.md` for its
contract and verification commands.

The current descriptor requires exactly six native ZIPs: all three CLI products
(`hexana-cli`, `hexana-cli-script`, `hexana-wasm-tools`) on macOS arm64 and Linux
x64, plus one identity per target. Keep fake-storage fixtures on that real
descriptor; the old four-archive input must fail. Native assembly and compatibility
verification are owned by `:mcp:verifyNativeCliReleaseDescriptor` and
`:mcp:verifyNativeCliReleaseArchives`, with GraalVM 25+ required for execution.

Run the automated packaging gate after validator or publication changes:

```bash
./gradlew verifyCliReleasePackaging
```

It runs the validator unit tests and the fake-storage publisher self-test in
the TeamCity CLI lane. The self-test can also be run directly:

```bash
CLI_DOWNLOADS_BUNDLE_SELF_TEST=true bash cli/packaging/publish-cli-downloads-release-bundle.sh
```

Keep artifact paths, output paths, release policy, and destination validation
within this directory. The S3 bucket comes from `HEXANA_DOWNLOADS_S3_BUCKET` (required when publishing); never
hardcode it. Do not add unrelated product or wrapper policy here.

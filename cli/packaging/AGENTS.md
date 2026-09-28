# AGENTS.md for `cli/packaging`

`publish-cli-downloads-release-bundle.sh` is the CLI downloads publisher. It
accepts the descriptor-declared verified native archives and platform identity
files, then owns the immutable release ledger, manifest, checksums, README, and
CLI release index. The generated descriptor is
`cli/metadata/src/main/gen/cli-release-artifacts.json`; see `README.md` for its
contract and verification commands.

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
within this directory. Do not add unrelated product or wrapper policy here.

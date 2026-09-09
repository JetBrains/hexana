# AGENTS.md for `cli/packaging`

`publish-cli-downloads-release-bundle.sh` is the CLI downloads publisher. It
accepts exactly four verified native archives and two platform identity files,
then owns the immutable release ledger, manifest, checksums, README, and CLI
release index.

Use its fake-storage self-test for publication changes:

```bash
CLI_DOWNLOADS_BUNDLE_SELF_TEST=true bash cli/packaging/publish-cli-downloads-release-bundle.sh
```

Keep artifact paths, output paths, release policy, and destination validation
within this directory. Do not add unrelated product or wrapper policy here.

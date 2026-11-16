# AGENTS.md for `mcp/packaging`

This folder contains client-specific wrapper layouts for the standalone MCP runtime in `mcp/server`.

## Ownership Boundary

- Keep this folder packaging-only: manifests, `.mcp.json`, launcher scripts, README files, and distribution license
  files.
- The checked-in Claude Desktop MCPB manifest tool inventory is generated from the standalone default MCP profile;
  packaging must not add or remove tools independently.
- Do not duplicate MCP runtime logic here. The executable source of truth stays in `mcp/server`.
- Wrapper scripts should prefer a bundled `server/bin/server` runtime inside the assembled package.
- `HEXANA_SERVER_BIN` may override the bundled runtime for local testing.
- The source `.mcp.json` files in this folder must be safe for distributable ZIP assembly. Do not let
  `./gradlew :mcp:assembleMcpPackaging` write local absolute paths into ZIP artifacts.
- Repo-local Codex install may rewrite `.mcp.json` to an absolute launcher path under
  `build/repo-local-plugin/plugins/hexana/bin/hexana-mcp` for local testing only.
- `installRepoLocalClaudePlugin` and `installRepoLocalCodexPlugin` must install only under the gitignored
  `build/repo-local-plugin/`. The repo-root `plugins/hexana/`, `.agents/plugins/marketplace.json` and
  `.claude-plugin/marketplace.json` are the tracked public marketplace layout (`hexana` marketplace); never write dev
  builds there.
- Keep local-first defaults. The packaged wrappers should launch the standalone server in `stdio` mode unless a client
  manifest explicitly requires something else.
- `release-codex-claude-plugins.sh` owns the Codex/Claude release mechanics: package artifacts, GitHub release asset
  upload, public bundle validation, marketplace PR publishing, and public-file safety checks. TeamCity should
  orchestrate branches, credentials, and gates, then call this script instead of duplicating release shell logic in
  Kotlin DSL.
- `publish-downloads-release-bundle.sh` owns the independent downloads.jetbrains.com MCP and CLI products. The MCP
  version directory contains only native MCP plus Claude/Codex wrapper ZIPs, checksums, and its manifest. The CLI
  version directory contains only the two split native CLI ZIP families, checksums, a rendered version-local README,
  and its manifest. Each product has its own merged root `releases.json` and guarded S3 prefix. The script must not
  rebuild artifacts. The S3 bucket comes from `HEXANA_DOWNLOADS_S3_BUCKET` (required when publishing); never hardcode it.
- `CLI_DOWNLOADS_README.md.template` is internal release input. Only its rendered `README.md` belongs in the versioned
  CLI downloads directory; do not publish it at the CLI downloads root.
- Public Claude/Codex release packages run R8 at build time and ship `server/lib/hexana-runtime-obfuscated.jar`.
  Do not ship R8 itself, `mcp/build/r8/mapping.txt`, or the pre-obfuscation Hexana-owned runtime jars.

## Third-Party Notices

- `THIRD-PARTY-NOTICES.md` in every JVM and native bundle comes from `:mcp:generateThirdPartyNotices`, which uses the
  shared `gradle/third-party-notices.gradle.kts`. It covers the union of the MCP server and CLI runtime classpaths, the
  Rust crates of embedded Wasm modules found on them, and the GraalVM Community native-image runtime
  (GPL-2.0 WITH Classpath-exception-2.0, labelled as applying to native executables). The packaging validators compare
  the bundled file byte for byte with the generated one.

## Testing

- Run `./gradlew :mcp:assembleMcpPackagingPlain :mcp:server:plainMcpPackagingSmoke` to verify the pre-R8 JVM fallback
  runtime plus Claude and Codex wrappers.
- Run `./gradlew :mcp:assembleMcpPackaging :mcp:server:obfuscatedMcpPackagingSmoke` to verify the R8-obfuscated JVM
  fallback runtime plus Claude and Codex wrappers. These checks are compatibility gates; native matrix artifacts are
  still required for release readiness.
- For R8 rule or obfuscated-runtime layout changes, also run
  `./gradlew :mcp:server:obfuscatedMcpPackagingSmoke --rerun-tasks`.
- Release ZIPs must pass `release-codex-claude-plugins.sh`'s artifact security check before any GitHub Release upload.
  Before extraction, the check rejects unsafe/control-character paths, path collisions, and symlinks. It also fails on
  local machine paths, internal repository URLs, token names/patterns (the internal parameter names come from
  `HEXANA_LEAK_GUARD_INTERNAL_NAMES`; never hardcode them; required for release runs, optional in the
  validation-only mode), private-key markers, source files, private/build
  path names, R8 mapping output, and pre-obfuscation Hexana runtime jars.
- To run only that authoritative policy against already-built ZIPs, set
  `RELEASE_ARTIFACT_SECURITY_CHECK_DIR` to their exact directory. This validation-only mode exits before builds and
  publication setup, so it needs local scanning tools but no GitHub token, repository, or git-author environment.
- For changes to `release-codex-claude-plugins.sh`, run `bash -n mcp/packaging/release-codex-claude-plugins.sh`.
  Use `DRY_RUN=true` against a disposable public-repo clone or fixture when credentials are available.
- For changes to `publish-downloads-release-bundle.sh`, run
  `bash -n mcp/packaging/publish-downloads-release-bundle.sh` and
  `MCP_DOWNLOADS_BUNDLE_SELF_TEST=true bash mcp/packaging/publish-downloads-release-bundle.sh`.
- Do not point clients at source-tree wrapper layouts unless you are explicitly testing packaging templates. Build with
  `./gradlew :mcp:assembleMcpPackaging` first so manifests and bundled runtime layout match the release artifact.
- Prefer smoke checks like `mcp/build/packaging/claude-plugin/bin/hexana-mcp version` and
  `mcp/build/packaging/codex-plugin/bin/hexana-mcp version` over ad hoc script edits.
- For Codex repo-local install-flow changes, run `./gradlew :mcp:installRepoLocalCodexPlugin` and verify the installed
  layout under `build/repo-local-plugin/plugins/hexana/` plus `build/repo-local-plugin/.agents/plugins/marketplace.json`,
  and that `git status` stays clean (the tracked root `plugins/hexana/` must not change).
- If a Codex packaging change could affect plugin discovery, installation, or bundled MCP wiring, also run the UI smoke
  test `./gradlew :idea-plugin:buildPlugin :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaMcpPackagedSmokeTest`
  with `HEXANA_USE_LOCAL_PLUGIN=true` and `HEXANA_LOCAL_PLUGIN_ZIP` pointing at the built IntelliJ plugin ZIP.

# MCP Packaging

This directory contains client-specific wrapper templates for the standalone runtime in `mcp/server`.

Related documents:

- [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) lists the JVM and native smoke gates for releases.
- [Hexana MCP Native Image Release Design](../../docs/superpowers/specs/2026-06-19-mcp-native-image-release-design.md)
  records the native package support policy and current limitations.

Source layouts:

- `claude-plugin/`
- `codex-plugin/`

Build release wrapper outputs with R8-obfuscated Hexana runtime classes:

```bash
./gradlew :mcp:assembleMcpPackaging
```

Build pre-R8 wrapper outputs for comparison/smoke gates with:

```bash
./gradlew :mcp:assembleMcpPackagingPlain
```

Build native-enabled wrapper outputs for the current host platform with:

```bash
./gradlew :mcp:assembleNativeMcpPackaging
```

Build and smoke-test native release artifacts for the current host platform with:

```bash
./gradlew :mcp:assembleNativeMcpReleaseArtifacts
```

Native builds require a GraalVM distribution with `native-image` installed. Set `GRAALVM_HOME` or `JAVA_HOME` to that
distribution before running native tasks if Gradle does not auto-detect it. JBR and ordinary JDKs can run JVM package
gates, but they cannot rebuild the native executable.

Install the Codex plugin into the repo-local marketplace layout with:

```bash
./gradlew :mcp:installRepoLocalCodexPlugin
```

This produces:

```text
mcp/build/packaging/claude-plugin/
mcp/build/packaging/codex-plugin/
mcp/build/distributions/
```

The native-enabled task produces outputs under:

```text
mcp/build/packaging-native/claude-plugin/
mcp/build/packaging-native/codex-plugin/
mcp/build/distributions-native/
```

The pre-R8 task produces non-release smoke outputs under:

```text
mcp/build/packaging-plain/claude-plugin/
mcp/build/packaging-plain/codex-plugin/
mcp/build/distributions-plain/
```

and for repo-local Codex testing:

```text
.agents/plugins/marketplace.json
plugins/hexana/
```

## Package layout and fallback

Each assembled wrapper includes its own bundled standalone JVM runtime under `server/` and exposes `bin/hexana-mcp`.
Native-enabled wrappers also include `native/<platform>/hexana-mcp` for the current host platform, or
`native/<platform>/hexana-mcp.exe` on Windows. The supported native release matrix uses these platform tags:

- `macos-arm64`
- `linux-x64`
- `windows-x64`

The launcher first
uses `HEXANA_SERVER_BIN` when explicitly set, then prefers the bundled native executable matching the current platform,
then falls back to `server/bin/server`.
Unsupported platforms keep working through the bundled JVM runtime; the launcher does not try to run a native
executable built for a different platform.
Release ZIPs must not contain build-machine absolute paths. The Claude package uses Claude's plugin-root variable, and
the Codex package keeps its `.mcp.json` launcher path and working directory relative to the plugin root.

Public Claude/Codex release packages use R8 at build time to obfuscate Hexana-owned runtime bytecode. The obfuscated
runtime is shipped as `server/lib/hexana-runtime-obfuscated.jar`; third-party runtime dependencies remain as normal
library jars. R8 itself and `mcp/build/r8/mapping.txt` are build outputs only and must not be shipped in plugin or
server ZIPs.

The public runtime layout intentionally remains a Gradle `installDist` style directory instead of a fat JAR for the
first marketplace release path. A fat JAR would need separate validation for merged service descriptors, license and
notice handling, native/JNA resources, Kotlin serialization metadata, classpath-sensitive libraries, and R8 behavior.

The `.mcp.json` files checked into `mcp/packaging/*` are the source templates for distributable wrappers. Repo-local
Codex install is the exception: `./gradlew :mcp:installRepoLocalCodexPlugin` rewrites the installed `plugins/hexana/.mcp.json`
to an absolute launcher path for local testing.

For ad hoc local testing, you can still override the launcher path explicitly:

```bash
HEXANA_SERVER_BIN=/absolute/path/to/server/bin/server mcp/packaging/claude-plugin/bin/hexana-mcp version
```

When testing a native-enabled assembled package, run the wrapper from the assembled plugin root:

```bash
mcp/build/packaging-native/claude-plugin/bin/hexana-mcp version
```

For Codex local plugin testing, run `./gradlew :mcp:installRepoLocalCodexPlugin`, restart Codex, and install
`Hexana` from the `Hexana Local` marketplace in the plugin directory (`/plugins` in CLI).

## Native support policy

Native MCP releases are stdio-first. The release artifact task runs `hexana-mcp version` and the native stdio MCP smoke
test before writing current-host artifacts. Native HTTP support is validated by the separate
`:mcp:server:nativeMcpHttpSmoke` task; it is not part of `:mcp:assembleNativeMcpReleaseArtifacts` until the HTTP path is
proven on the supported platform matrix.

JVM standalone MCP analytics are enabled by default for `stdio` and `http`. GraalVM Native Image MCP analytics are
disabled by default because PostHog networking and shutdown-hook behavior adds runtime surface to native startup and
smoke reliability. Native users can opt in explicitly with `--telemetry-enabled true` or
`HEXANA_MCP_TELEMETRY_ENABLED=true`; explicit opt-out works everywhere.

## Codex/Claude release automation

`release-codex-claude-plugins.sh` owns the release mechanics for the Claude Code and Codex plugin wrappers. TeamCity
should use it after the release build's snapshot dependencies have passed. The script:

1. builds pre-R8 standalone, Claude, and Codex MCP packages,
2. smoke-tests the pre-R8 standalone runtime plus Claude and Codex wrappers,
3. builds R8-obfuscated release standalone, Claude, and Codex MCP packages,
4. smoke-tests the R8-obfuscated standalone runtime plus Claude and Codex wrappers,
5. runs the release artifact security check,
6. validates the generated public marketplace bundle JSON files and native artifact policy,
7. validates, commits, pushes, and creates or updates the public marketplace pull request,
8. creates or updates the GitHub Release and uploads ZIP assets.

TeamCity stays responsible for branch policy, secure parameters, build-chain dependencies, and notifications. The
packaging repository stays responsible for the release mechanics and public-file safety rules.

Native release artifacts are built by a platform CI matrix rather than by the GitHub release script on one host. Each
matrix leg must run:

```bash
./gradlew :mcp:assembleNativeMcpReleaseArtifacts
```

That task builds the current host's GraalVM Native Image executable, requires `hexana-mcp version` to print exactly
`hexana-mcp <mcpServerVersion>` from `mcp/server/gradle.properties`, runs the native stdio MCP smoke test, and writes
versioned artifacts to `mcp/build/distributions-native/`:

- `hexana-mcp-native-<platform>-<version>.zip`: standalone native executable with license files.
- `hexana-claude-plugin-<platform>-<version>.zip`: Claude wrapper with the native executable and JVM fallback.
- `hexana-codex-plugin-<platform>-<version>.zip`: Codex wrapper with the native executable and JVM fallback.

The release job should download all native matrix artifacts into one directory and set `INCLUDE_NATIVE_ARTIFACTS=true`
before running `release-codex-claude-plugins.sh`. By default the script requires `macos-arm64 linux-x64 windows-x64`;
override `NATIVE_RELEASE_PLATFORMS` only when intentionally changing the supported matrix. Unsupported platforms keep
using the bundled JVM runtime through the wrapper fallback.

Release script example:

```bash
GH_TOKEN=... \
PUBLIC_REPO_SLUG=owner/repo \
GENERATED_PUBLIC_DIR=mcp/out/teamcity/public-marketplace \
AUTO_MERGE=false \
mcp/packaging/release-codex-claude-plugins.sh
```

The release script reads `PLUGIN_VERSION` from `mcp/server/gradle.properties:mcpServerVersion` when the variable is not
set, defaults `RELEASE_TAG` to `v<PLUGIN_VERSION>`, and writes release artifacts under
`mcp/out/teamcity/release`.

The script never pushes to `main` or `master`. It creates a branch named
`automation/update-<plugin-name>-<version>`, copies only allowlisted public files from `GENERATED_PUBLIC_DIR`, validates
JSON files, scans the staged diff for private paths and obvious secret patterns, pushes the branch, and creates or
updates a GitHub PR. Re-running the same plugin/version updates the same branch and PR.

Required tools on the TeamCity agent:

- `bash`
- `git`
- `gh`
- `jar`
- `jq`
- `rsync`
- `strings`
- `unzip`
- `shasum` or `sha256sum`

Required environment variables:

- `GH_TOKEN` or `GITHUB_TOKEN`: fine-grained PAT or GitHub App token. Pass it as a TeamCity password parameter.
- `PUBLIC_REPO_SLUG`: public GitHub repository, for example `owner/repo`.
- `PLUGIN_NAME`: marketplace plugin directory name, for example `hexana`.
- `PLUGIN_VERSION`: version being published.
- `GENERATED_PUBLIC_DIR`: directory that already contains the public marketplace bundle.

Optional environment variables:

- `PUBLIC_REPO_BASE_BRANCH`: defaults to `main`.
- `RELEASE_TAG`: release tag to mention in the PR body, for example `v1.2.3`.
- `AUTO_MERGE`: defaults to `false`. When `true`, the script queues `gh pr merge --auto --rebase --delete-branch`.
- `DRY_RUN`: defaults to `false`. When `true`, the script clones, syncs, validates, scans, and prints a diff summary
  but does not commit, push, create/update a PR, or enable auto-merge.
- `PR_LABELS`: comma-separated labels to add to the PR.
- `PR_REVIEWERS`: comma-separated reviewers to request.
- `PUBLIC_REPO_CLONE_DIR`: reuse this local clone instead of a temporary clone.
- `COMMIT_AUTHOR_NAME`: required git commit author name for marketplace PR commits.
- `COMMIT_AUTHOR_EMAIL`: required git commit author email for marketplace PR commits. Use an approved bot, GitHub App,
  or noreply automation identity; do not use a personal email.
- `ALLOW_BUNDLED_JAR`: defaults to `false`. When `true`, jars are allowed only under
  `plugins/<plugin-name>/server/lib/`.
- `INCLUDE_NATIVE_ARTIFACTS`: defaults to `false`. When `true`, release collection requires native executable,
  Claude plugin, and Codex plugin ZIPs for every platform in `NATIVE_RELEASE_PLATFORMS`.
- `NATIVE_RELEASE_ARTIFACT_DIR`: defaults to `mcp/build/distributions-native`.
- `NATIVE_RELEASE_PLATFORMS`: defaults to `macos-arm64 linux-x64 windows-x64`.
- `VALIDATE_CLAUDE_CMD`: optional command run from the cloned public repo root.
- `VALIDATE_CODEX_CMD`: optional command run from the cloned public repo root.
- `SKIP_RELEASE_BUILD_AND_SMOKE`: defaults to `false`. TeamCity sets this to `true` only after it has already run the
  visible pre-R8 and R8-obfuscated Gradle smoke steps in the same checkout.

Public files allowlist:

- `.claude-plugin/marketplace.json`
- `.agents/plugins/marketplace.json`
- `plugins/<plugin-name>/.claude-plugin/**`
- `plugins/<plugin-name>/.codex-plugin/**`
- `plugins/<plugin-name>/.mcp.json`
- `plugins/<plugin-name>/release.lock.json`
- `plugins/<plugin-name>/README.md`
- `plugins/<plugin-name>/LICENSE.txt`
- `plugins/<plugin-name>/THIRD-PARTY-NOTICES.md`
- `plugins/<plugin-name>/skills/**`
- `plugins/<plugin-name>/mcp/**`
- `plugins/<plugin-name>/bin/**`
- `plugins/<plugin-name>/native/**`
- `plugins/<plugin-name>/server/**`; jars inside this tree are allowed only under
  `plugins/<plugin-name>/server/lib/**` with `ALLOW_BUNDLED_JAR=true`

Safety checks fail the build if the staged diff contains source/build/private paths such as `src/`, `build/`,
`target/`, `out/`, `.gradle/`, `.idea/`, `.teamcity/`, `.env`, `private/`, `secret/`, `secrets/`, source-like or
credential file extensions such as `*.java`, `*.kt`, `*.class`, `*.pem`, `*.key`, `*.p12`, `*.keystore`, `*.jks`, any
unexpected `*.jar`, symlinks, or obvious secret markers such as `BEGIN PRIVATE KEY`, `github_pat_`, `ghp_`, `gho_`,
`ghu_`, `ghs_`, `sk-`, `AKIA`, `password=`, `secret=`, or `token=`. It also fails on local machine paths such as
`/Users/` or `/private/`, this workspace name, internal repository hostnames, TeamCity/GitHub token variable names, and
the TeamCity GitHub-token parameter placeholder.

Before publishing GitHub Release assets, the script also extracts every release ZIP and scans the artifact contents.
This artifact security check fails on local machine paths such as `/Users/` or `/private/`, this workspace name,
internal repository hostnames, TeamCity/GitHub token variable names, private-key markers, obvious token prefixes,
source files, credential files, private/build path names, R8 mapping output, or pre-obfuscation Hexana runtime jars.
PostHog/analytics dependencies are allowed and are included in the obfuscated runtime jar.

`THIRD-PARTY-NOTICES.md` is generated by `./gradlew :mcp:generateThirdPartyNotices` from the MCP runtime classpath's
Maven POM license metadata and is copied into the plugin root and bundled server root during packaging. Legal files are
published inside `plugins/<plugin-name>/`; the public repository root must not receive standalone legal files from this
automation.

GitHub-facing text is scanned separately before public mutation calls. Release notes, PR title, PR body, and PR update
comments fail on internal CI wording, internal hostnames, local paths, token names or prefixes, and direct URLs. The
guard can be checked without network access by running
`PUBLIC_GITHUB_TEXT_GUARD_SELF_TEST=true ./mcp/packaging/release-codex-claude-plugins.sh`.

The secret scan is intentionally conservative. If it reports a false positive, either change the public file content so
the marker is not present verbatim, or adjust the script in the same review with a narrowly scoped exception. Do not
disable the scan in TeamCity.

Dry run example:

```bash
GH_TOKEN=... \
PUBLIC_REPO_SLUG=owner/repo \
PLUGIN_NAME=hexana \
PLUGIN_VERSION=1.2.3 \
GENERATED_PUBLIC_DIR=mcp/out/teamcity/public-marketplace \
DRY_RUN=true \
mcp/packaging/release-codex-claude-plugins.sh
```

Real PR example:

```bash
GH_TOKEN=... \
PUBLIC_REPO_SLUG=owner/repo \
PUBLIC_REPO_BASE_BRANCH=main \
PLUGIN_NAME=hexana \
PLUGIN_VERSION=1.2.3 \
GENERATED_PUBLIC_DIR=mcp/out/teamcity/public-marketplace \
RELEASE_TAG=v1.2.3 \
AUTO_MERGE=false \
mcp/packaging/release-codex-claude-plugins.sh
```

Use a fine-grained PAT or GitHub App token scoped to the public repository. Required permissions:

- Contents: read/write
- Pull requests: read/write
- Metadata: read

For TeamCity, add the token as a password parameter and expose it as `env.GH_TOKEN`. The release build should run:

```bash
./mcp/packaging/release-codex-claude-plugins.sh
```

Recommended TeamCity environment:

- `env.GH_TOKEN`: password parameter.
- `env.PUBLIC_REPO_SLUG`: public repository slug.
- `env.PUBLIC_REPO_BASE_BRANCH`: `main`.
- `env.PLUGIN_NAME`: `hexana`.
- `env.PLUGIN_VERSION`: release or build version.
- `env.GENERATED_PUBLIC_DIR`: path to the generated public marketplace bundle.
- `env.RELEASE_TAG`: `v<version>` when there is a release tag.
- `env.AUTO_MERGE`: `false` initially.
- `env.DRY_RUN`: `true` for the first TeamCity wiring test, then `false`.

Do not hardcode GitHub tokens in Kotlin DSL, shell scripts, XML, or committed config. Enable `AUTO_MERGE=true` only
after branch protection and required checks are configured in the public GitHub repository and the manual PR flow has
been verified.

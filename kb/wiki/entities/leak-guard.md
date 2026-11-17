# Leak guard and contributor build findings

How new internal references are kept out of the tree, and what a build on a clean machine without JetBrains network
access needs. Decisions: open-sourcing preparation, 2026-11-13.

## Contributor build findings

A one-off clean-container run (fresh Gradle home, no credentials, internal hosts blocked at DNS) was used to write the
prerequisites in `CONTRIBUTING.md`. Neither that harness nor a dedicated contributor aggregate task is kept
(2026-11-13): nothing would run them regularly, so they would drift.

What that run of `./gradlew build` on the tip showed:

| Failure | Cause | Outcome |
| --- | --- | --- |
| Build does not configure: no Java 21 installation matching the toolchain | Java 21 toolchains are declared, no toolchain download repository is configured | prerequisite: install JDK 21 next to JDK 25 |
| About 56 `jsBrowserTest`/`wasmJsBrowserTest` tasks: "did not discover any tests" | no Chrome (`hx.tests.web.enableChromeOrChromium=1` is the default); the same task passes on a host with Chrome | Chrome is optional; documented in `CONTRIBUTING.md` |
| `detekt` on the CLI and MCP modules | detekt cannot handle JDK 25.x version strings; fails the same way on a clean checkout with JDK 25.0.4 | documented in `CONTRIBUTING.md`; run module test tasks instead of `build` |
| `:ui-driver-tests:test` | needs the internal TeamCity server | internal-only, left out |
| `:idea-plugin:test`, `PartialViewEditorTest` | "No space left on device": the test writes a sparse file of about 3 GB and the Docker VM disk was nearly full; passes on a host | environment; disk-space note in `CONTRIBUTING.md` |
| `:disassembly:capstone-wasm:jsNodeTest` | the root build switches every `KotlinJsTest`, node tests included, to Karma with Chrome (no `karma.config.d/web` in that module), so without Chrome no test is discovered | left out |

`cache-redirector.jetbrains.com` is anonymous but answers with a redirect to a public CDN host; a DNS blocklist that
blocks that host breaks every Maven resolution. `packages.jetbrains.team/maven/p/ij/intellij-dependencies` (also used for
the Patronus config library) resolves anonymously. No build step contacted a blocked internal host.

## Leak guard

Two independent checks, both scanning the tree (not history):

- **Internal TeamCity build, registered as a Patronus check for every path** (the check is declared in
  `.patronus/patronus-config`).
  It scans with a private pattern list (internal hosts, ID prefixes, named employee paths, Slack links, the private CI
  configuration name), a private allowlist, and gitleaks. Those files live only in the private CI configuration
  repository, and the log shows file locations, never matched text. Allowlist entries marked `PENDING` are known leaks
  that are still in the tree and are printed on every run.
- **GitHub Actions** (`.github/workflows/leak-guard.yml`): public, generic rules only: gitleaks default rules,
  macOS home-directory paths in text files, and analytics key prefixes. It runs on `push` and `pull_request`,
  with a read-only token and no secrets, never `pull_request_target`. `.github/leak-guard-allowlist.txt` lists public
  files that legitimately contain such paths (path-matching code and tests, release-script self-tests).
  `.gitleaks.toml` allowlists one truncated PEM header used as test input.

Changing the guard: patterns and allowlist are edited in the private repository (no Hexana change). Bump gitleaks in
both the private `gitleaks.py` and the workflow, with the new release checksum. Never copy the private patterns into
this repository; the guard fails any file that contains them.

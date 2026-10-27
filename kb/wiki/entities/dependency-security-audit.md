# Dependency security audit

How to audit Hexana's external dependencies for known vulnerabilities, where the
dependency surfaces are, and the results of the 2026-10-27 audit.

## Dependency surfaces

| Surface | Where versions live | Ships to users? |
| --- | --- | --- |
| JVM/KMP libraries + Gradle plugins | `gradle/libs.versions.toml`; a few hard-coded coordinates in `disassembly/capstone-wasm/build.gradle.kts` (chicory, redline) and `jvm-jit-jmh/build.gradle.kts` (jmh, asm) | yes (plugin ZIP, MCP server, CLI products) |
| VS Code extension npm | `vscode-plugin/package.json` + lock, with `overrides` | build/publish tooling (`vsce`, `ovsx`) + runtime `adm-zip`, `semver` |
| VS Code UI-test npm | `vscode-plugin/test/package.json` + lock, with `overrides` | no (test only) |
| Rust → embedded `.wasm` | `wasm-modules/*/Cargo.toml` (no committed `Cargo.lock`; `wat2wasm` tracks wasm-tools git HEAD) | yes, as prebuilt `.wasm` in `graalwasm/` |
| Docker images | `webapp/Dockerfile` (node verifier stages + pinned nginx final image), fixture Dockerfiles (`debian:bookworm-slim`, `rust:1-bookworm`) | only the webapp nginx final image |

## Method

1. npm: `npm audit` in `vscode-plugin/` and `vscode-plugin/test/`. When a fix needs a major
   bump that a parent's range does not allow, add or adjust an `overrides` entry (the main
   package already overrides `@azure/msal-node`, `markdown-it`, `form-data`, …).
2. JVM: dump resolved `runtimeClasspath` for the shipped modules
   (`:idea-plugin`, `:mcp:server`, `:mcp:scripting`, `:cli:hexana-cli`, `:cli:hexana-cli-script`,
   `:cli:hexana-wasm-tools`) using `./gradlew -q <p>:dependencies --configuration runtimeClasspath`,
   extract `group:artifact:version` (taking the `->` resolved version), and POST them to the
   OSV batch API `https://api.osv.dev/v1/querybatch` (ecosystem `Maven`). Use
   `https://api.osv.dev/v1/vulns/<id>` to get severity and fixed ranges.
3. Cargo: query OSV with ecosystem `crates.io` for the declared crates.
4. Check that a candidate fixed version is itself clean in OSV and published on Maven Central.

No osv-scanner, trivy, or cargo-audit is installed locally. The method above needs only
curl, python3, and npm.

## 2026-10-27 audit results

Updated:
- `jackson` 2.20.0 → 2.21.8. jackson-core and jackson-databind had multiple HIGH advisories
  (ReDoS, PTV bypasses, DoS), and no 2.20.x patch exists. The only consumer is
  `idea-plugin`'s `implementation(libs.jackson.module.kotlin)`, which has no source usages
  but is bundled.
- `logback` 1.5.32 → 1.5.38 (LOW: GHSA-jhq6-gfmj-v8fx, GHSA-p47f-322f-whfh). Used by `mcp:server`
  and the CLI products.
- `vscode-plugin/test`: `npm audit fix` (undici, markdown-it, fast-uri, http-cache-semantics),
  `serialize-javascript` override `^7.0.7` → `^7.1.3`, and a new `@azure/msal-node: 7.1.1` override
  that mirrors the main package.

Reviewed, not changed:
- `wee_alloc` 0.4 (RUSTSEC-2022-0054) is an unmaintained advisory, not a CVE. Replacing it
  means rebuilding the embedded `.wasm` converters.
- `node:22.14.0-bookworm-slim` in `webapp/Dockerfile` is an outdated Node 22, but it is used
  only in build-time verifier stages over our own artifacts and is not in the final image.
- All other resolved runtime JVM artifacts (153) and the declared crates had no OSV hits.

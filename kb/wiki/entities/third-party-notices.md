# Third-party notices

How every shipped Hexana artifact gets its `THIRD-PARTY-NOTICES.md`, where the licence texts come from, and what
fails when a dependency has no reviewed licence text. Decisions: HEXANA-33 (Part A, 2026-11-12).

## Generator

`gradle/third-party-notices.gradle.kts` is a script plugin. A project applies it and calls
`extra["registerThirdPartyNotices"](spec)`; the spec keys are documented at the top of the script. It is under
`gradle/`, so Patronus already routes changes to it to every product lane. Inputs:

- Maven/Kotlin-JS runtime classpaths: name, URL and licence from the POM (parent POMs followed up to five levels),
  plus the `LICENSE`/`NOTICE`/`COPYING` files inside each jar or klib.
- npm closures (`dependencies` of the Kotlin/JS `package.json` files under `build/js/packages`, and
  `vscode-plugin/node_modules`): `license` from `package.json` and the licence files in the package directory.
- `license/third_party/maven-licenses.json`: upstream texts for components that ship none (also npm, as `npm:<name>`).
- `license/third_party/components.json`: non-package components (GraalVM Community native-image runtime).
- `license/third_party/rust/crates.json`: Rust crates of the embedded `.wasm` modules. A module's crates are included
  when a jar/klib on the classpath contains its `.wasm` file, or when the spec names it in `rustModules`.

The task fails when a component has no licence name or no text, so a new dependency needs a reviewed rule.
Identical texts are written once in a "Licence and notice texts" appendix and referenced by number.

## Per artifact

| Artifact | Classpath used | Notes |
| --- | --- | --- |
| IntelliJ plugin ZIP | `intellijPlatformRuntimeClasspath` | NOT `runtimeClasspath`: the IntelliJ Platform Gradle plugin resolves some versions differently (`annotations-13.0` vs `23.0.0`), and the ZIP follows the former. File dependencies (`capstone-wasm-aot.jar`, `capstone-wasm-redline.jar`) are listed as `file` entries. The check compares every `lib/*.jar` of the built ZIP to the generated `components.tsv`. |
| VSIX | `jsRuntimeClasspath` of `extension-lib` and `webview` | needs `npm install` first (reads `vscode-plugin/node_modules`); `.vscodeignore` no longer drops `*.js.LICENSE.txt` |
| Webapp | `jsRuntimeClasspath`, `jsParserWorkerRuntimeClasspath` | `THIRD-PARTY-NOTICES.md` is in the package allowlist, the checksums and the OCI evidence names (`oci-evidence-contract.mjs`, `deploy-webapp-release.mjs`) |
| MCP/CLI bundles | runtime classpaths of `:mcp:server` and the three CLI products | one file for JVM and native bundles; the GraalVM runtime section is labelled "native-image executables only" |

## Gotchas

- IntelliJ-side files are copied into the plugin directory (`hexana/`), not `hexana/lib/`.
- Gradle `ResolvedArtifactResult` for Kotlin/JS klibs works through `incoming.artifacts` on `jsRuntimeClasspath`.
- `@posthog/core` ships no licence file and declares MIT while the monorepo root licence is Apache-2.0 with a
  Mixpanel notice; the posthog-js and posthog-node packages ship that Apache text. Recorded as UNVERIFIED in the rule.
- jffi and JNA are dual-licensed; `noticeLicenseNotes` records the choice (Apache-2.0).
- The Rust crate versions in `Cargo.lock` follow the lock, which pins the versions visible in the embedded binaries
  and leaves the rest at what cargo resolved on 2026-11-12; the embedded `.wasm` files were not rebuilt.

# Licences and third-party notices

This directory holds the licence material of Hexana that is not tied to one source file.

## Licences of Hexana itself

- **Source code: Apache License 2.0.** The text is in `LICENSE.txt` at the repository root (so that GitHub detects it) and
  in `license/LICENSE.txt`, a byte-identical copy. Both are the exact text of
  <https://www.apache.org/licenses/LICENSE-2.0.txt>. There is no `NOTICE` file and the source files carry no licence
  headers.
- **Official binaries keep their JetBrains licences.** They are distributed by JetBrains under the licences included
  with them, which this repository keeps unchanged:
  - `vscode-plugin/LICENSE`, the JetBrains User Agreement, for the VS Code extension (VSIX);
  - `mcp/packaging/LICENSE.txt`, the JetBrains Free Plugin License, for the MCP and CLI bundles, the Claude and Codex
    plugins, the MCPB bundles and the native ZIPs.

Third-party components are covered separately, as described below.

## What is in this directory

- `LICENSE.txt` is the Apache-2.0 text of the source code (see above).
- `third_party/` has one subdirectory per bundled third-party component or licence family that cannot be taken from a
  published Maven or npm package: the licence texts exactly as published upstream, plus the machine-readable indexes the
  notices generator reads.
- Licence files that belong to a test fixture live next to that fixture, not here (for example
  `wasmParser/src/commonTest/resources/webImage/` and `ui-driver-tests/testData/fixtures/`). Fixtures are test data and
  are not shipped in any release artifact.

## What each artifact ships

Every shipped artifact contains a generated `THIRD-PARTY-NOTICES.md`. It is produced by
`gradle/third-party-notices.gradle.kts` from the runtime dependencies that are packaged into that artifact, so the list
cannot drift from the artifact:

| Artifact | Generator task | Lands at | Verified by |
| --- | --- | --- | --- |
| IntelliJ plugin ZIP | `:idea-plugin:generateThirdPartyNotices` | `hexana/THIRD-PARTY-NOTICES.md` inside the ZIP | `:idea-plugin:verifyPluginThirdPartyNotices` (runs after `buildPlugin`) |
| VS Code extension (VSIX) | `:vscode-plugin:generateThirdPartyNotices` | `extension/THIRD-PARTY-NOTICES.md` | `vscode-plugin/scripts/verify-vsix-notices.js` (runs from `build-release-package.sh`) |
| Webapp static package | `:webapp:generateThirdPartyNotices` | `THIRD-PARTY-NOTICES.md` in the package root, part of the package allowlist and OCI evidence | `:webapp:verifyProductionPackage` |
| MCP server and CLI bundles (JVM and native) | `:mcp:generateThirdPartyNotices` | `THIRD-PARTY-NOTICES.md` in every bundle | the byte-equality checks in `mcp/build.gradle.kts` and the CLI packaging validators |

Each file lists, per component, its name, project URL and licence as declared by its metadata, followed by the licence
and notice texts. The texts come from, in this order:

1. the `LICENSE`/`NOTICE`/`COPYING` files shipped inside the component's jar or npm package;
2. the rules in `third_party/maven-licenses.json` (a Maven or npm component with no usable text of its own);
3. for Apache-2.0 components that ship no text, `third_party/licenses/Apache-2.0.txt`;
4. `third_party/components.json` and `third_party/rust/crates.json` for components that are not Maven or npm packages.

The generator fails when a component has no licence name or no text, so a new dependency cannot ship without a
reviewed entry.

## What is in `third_party/`

| Path | Content | Upstream source |
| --- | --- | --- |
| `components.json` | components that are neither Maven nor npm packages (currently the GraalVM Community native-image runtime) | see the `url` of each entry |
| `maven-licenses.json` | rules that add upstream licence texts to Maven/npm components, with the source URL of every text | see the `source` of each rule |
| `licenses/` | canonical licence texts used as fallbacks | `Apache-2.0.txt`: <https://www.apache.org/licenses/LICENSE-2.0.txt>; `0BSD.txt`: SPDX license-list-data v3.29.0 |
| `graalvm-community/LICENSE` | product licence of GraalVM Community Edition (GPL-2.0 with the Classpath Exception), shipped with native executables | <https://github.com/oracle/graal/blob/vm-25.0.0/LICENSE> |
| `graalvm/` | UPL-1.0 text of the GraalVM SDK, Truffle, regex and Wasm libraries; GraalJS licence | <https://github.com/oracle/graal/blob/vm-25.0.0/sdk/LICENSE.md>, <https://github.com/oracle/graaljs/blob/vm-25.0.0/LICENSE> |
| `asm/`, `icu/`, `colormath/`, `kotlin-sdk/`, `jffi/`, `posthog/` | licence files of components whose artifacts ship none | the tag or revision recorded in `maven-licenses.json` |
| `rust/` | licence texts and `crates.json` of every Rust crate linked into the embedded Wasm modules built from `wasm-modules/`; regeneration steps are in `rust/README.md` | crates.io and the pinned `bytecodealliance/wasm-tools` revision |

## Maintenance

- A new Maven or npm dependency needs no entry when its jar or package ships its licence text. If the generator
  reports a component without a text, add a rule to `maven-licenses.json` with the exact upstream file and its URL.
- A new embedded Wasm module needs its crates in `rust/crates.json` (see `rust/README.md`) and its file name in
  `embeddedWasmModules` in `gradle/third-party-notices.gradle.kts`.
- A component offered under several licences gets a note in `noticeLicenseNotes` in the same script, stating which
  licence Hexana uses and where that choice is documented upstream.
- Anything that could not be verified upstream is marked `UNVERIFIED` in the rule or crate entry that carries it.

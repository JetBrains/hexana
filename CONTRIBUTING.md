# Contributing to Hexana

Thank you for your interest in Hexana.

## External pull requests are not accepted yet

The contribution workflow is not set up yet, so **external pull requests are not accepted. Please open an issue
instead.** A pull request opened from a fork may be closed with a pointer to this page.

Issues are welcome and are the best way to help right now:

- **Bug reports.** Include the Hexana version, the host (IntelliJ IDEA, VS Code, CLI or MCP server), the IDE or VS Code
  version, the operating system, and steps to reproduce. If a binary triggers the problem, say which format it is. Do
  not attach binaries you are not allowed to share.
- **Feature requests.** Describe the question you are trying to answer about a binary, not only the UI you have in mind.
- **Security problems.** Do not file them as public issues. Follow [SECURITY.md](SECURITY.md).

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Building and testing from source

The repository is a Gradle build (use the wrapper, `./gradlew`). Building the plugin and running the JVM tests needs no
VPN, no credentials and no `~/.gradle/gradle.properties`; every repository it uses is public and is reached anonymously.

```bash
./gradlew :idea-plugin:buildPlugin
```

This builds the IntelliJ plugin ZIP. Then run the tests of the module you change (see [Common commands](#common-commands)).

### Prerequisites

- **JDK 25 and JDK 21, both installed.** Run Gradle on JDK 25. The Java 21 toolchains are not downloaded for you, so
  Gradle must find a JDK 21 on the machine (for example under `/usr/lib/jvm`, SDKMAN or `JAVA_HOME`-style locations);
  without it the build stops while configuring.
- **Network access** to public hosts only: `services.gradle.org` (Gradle distribution), Maven Central and the Gradle
  Plugin Portal through `cache-redirector.jetbrains.com` (it redirects to a public CDN), Google Maven, `dl.google.com`,
  `packages.jetbrains.team` (the anonymous `intellij-dependencies` repository), `www.jetbrains.com` and the JetBrains
  download CDN (the IntelliJ IDEA 2026.2 distribution the plugin builds against), `plugins.jetbrains.com`,
  and `redirector.kotlinlang.org`. No proxy is required.
- **Memory and disk:** the Gradle daemon is configured with `-Xmx8g`, so keep at least 8 GB of RAM free. Leave
  generous free disk space (the IDE distribution, the Gradle caches and the build output are large); a plugin test creates
  a sparse file of about 3 GB and fails with `No space left on device` if the disk is full.

### Optional tools and internal modules

- **Chrome or Chromium**, for the Kotlin/JS and Wasm browser tests. `./gradlew build` includes them
  (`hx.tests.web.enableChromeOrChromium=1` in `gradle.properties`) and fails without a browser.
- **Node.js** is downloaded by the Kotlin Gradle plugin for JS/Wasm targets; install npm only for `vscode-plugin/`.
- **Docker**, only to rebuild the embedded `.wasm` converters in `wasm-modules/` (the built binaries are committed).
- **GraalVM 25+**, only for the native CLI tests (`nativeIntegrationTest`).
- **Rust (`cargo`)**, only for the webapp component build.
- **detekt** (`./gradlew build` runs it on the CLI and MCP modules) currently fails on JDK 25.x, so `build` is not
  green yet; run the module test tasks instead.
- **`ui-driver-tests`** is internal: it downloads IDE and plugin builds from JetBrains' CI server. It is not a
  contributor step.

### Common commands

The full command list is in [AGENTS.md](AGENTS.md); run the narrowest command that covers your change.

```bash
./gradlew :idea-plugin:runIde          # sandbox IDE with the plugin loaded
./gradlew :idea-plugin:test            # IntelliJ plugin tests
./gradlew :wasmParser:jvmTest          # Wasm parser tests
./gradlew :plugins-shared:build        # shared logic used by IntelliJ and VS Code
./gradlew :mcp:server:build            # standalone MCP server
```

Some test fixtures and tools are optional and read their location from environment variables, for example
`HEXANA_CORPUS_DIR`, `HEXANA_SAMPLE_COMPONENT_MODEL_DIR` and `ZEPHYR_SDK_INSTALL_DIR`. Tests that need them skip when
they are unset.

## When pull requests open up

These are the conventions we will ask for, so you can prepare:

- Keep a change focused on one module and add or update tests for behavior changes.
- Use commit messages of the form `area - message`, for example `pcap - add protocol family filter`.
- Do not hand-edit generated files (`idea-plugin/src/main/gen/**`, `mcp/server/src/main/gen/**`,
  `cli/metadata/src/main/gen/**`); change their source and regenerate, as described in [AGENTS.md](AGENTS.md).
- Read the nearest nested `AGENTS.md` for the module you change.

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

The repository is a Gradle build (use the wrapper). The build JDK and the full command list are in
[AGENTS.md](AGENTS.md); run the narrowest command that covers your change.

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

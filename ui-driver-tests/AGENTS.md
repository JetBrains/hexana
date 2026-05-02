# AGENTS.md for `ui-driver-tests`

This module contains expensive end-to-end UI tests for the plugin using JetBrains IDE Starter + Driver.

## What These Tests Actually Do

- Download an IntelliJ IDEA installer from TeamCity.
- Resolve a Hexana plugin ZIP either from a local file or from TeamCity.
- Install the plugin into the test IDE.
- Open a sample project from `testData/projects/hexana_wasm_project`.
- Verify that `hello.wasm` can be opened successfully.
- Run Wasm configurations with Wasmtime (core modules, exported functions, component models).

Main test entry points:

- `src/test/kotlin/org/jetbrains/hexana/ui/HexanaWasmProjectDriverTest.kt`
- `src/test/kotlin/org/jetbrains/hexana/ui/HexanaRunConfigurationDriverTest.kt`

Support code:

- `HexanaTeamCityArtifacts.kt`
- `HexanaTeamCityIdeDownloader.kt`

## When To Touch This Module

- Plugin installation flow changed.
- Plugin packaging or ZIP layout changed.
- Sandbox IDE startup behavior changed.
- End-to-end file-opening behavior changed in ways unit tests cannot cover.
- Run configuration or execution behavior changed for Wasm engines.

Do not use this module as the default validation path for normal parser or editor changes.

## Run Command

- `./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaWasmProjectDriverTest`
- `./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaRunConfigurationDriverTest`

## Required Environment Configuration

Exactly one plugin source mode must be enabled:

- `HEXANA_USE_LOCAL_PLUGIN=true` together with `HEXANA_LOCAL_PLUGIN_ZIP=/path/to/plugin.zip`
- `HEXANA_DOWNLOAD_PLUGIN=true`

If both are enabled or neither is enabled, the test is expected to fail fast.

Other inputs:

- `HEXANA_PLUGIN_BUILD_NUMBER`: optional TeamCity build number when downloading a plugin
- `HEXANA_IDE_BUILD_TYPE`: IDE build type, defaults to `IdeInstallers_Idea_Installers`
- TeamCity auth if guest access is insufficient:
  - `TEAMCITY_USERNAME` and `TEAMCITY_PASSWORD`
  - or JVM properties `teamcity.user` and `teamcity.token`
  - or `teamcity.local.properties` in the repo root, optionally redirected with `HEXANA_TEAMCITY_PROPERTIES`

## Practical Rules

- Treat these tests as networked integration tests, not unit tests.
- Expect slow execution and external failure modes.
- Prefer running them only after narrower module tests are already green.
- If you change the plugin ZIP source logic, update the README in this module too.

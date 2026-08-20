# AGENTS.md for `ui-driver-tests`

This module contains expensive end-to-end UI tests for the plugin using JetBrains IDE Starter + Driver.

## What These Tests Actually Do

- Download an IntelliJ IDEA installer from TeamCity.
- Resolve a Hexana plugin ZIP either from a local file or from TeamCity.
- Install the plugin into the test IDE.
- Open a sample project from `testData/projects/hexana_wasm_project`.
- Verify that `hello.wasm` can be opened successfully.
- Run/debug Wasm configurations with Wasmtime and Node.js (core modules, exported functions, component models, DWARF breakpoints).
- Assert the "Wasm" debugger tab shows the paused function's disassembly and steps one wasm instruction at a time.
- Exercise Hexana's Compose editor through a hybrid Driver plus plugin-side `@Remote` probe approach.
- Run a packaged-plugin MCP smoke against the IDE MCP server and Hexana MCP tools.

Main test entry points:

- `src/test/kotlin/org/jetbrains/hexana/ui/HexanaWasmProjectDriverTest.kt`
- `src/test/kotlin/org/jetbrains/hexana/ui/HexanaRunConfigurationDriverTest.kt`
- `src/test/kotlin/org/jetbrains/hexana/ui/HexanaWasmDebuggerTabDriverTest.kt`
- `src/test/kotlin/org/jetbrains/hexana/ui/HexanaEditorSurfaceSmokeTest.kt`
- `src/test/kotlin/org/jetbrains/hexana/ui/HexanaSettingsSmokeTest.kt`
- `src/test/kotlin/org/jetbrains/hexana/ui/HexanaMcpPackagedSmokeTest.kt`

Support code:

- `src/test/kotlin/org/jetbrains/hexana/ui/framework/HexanaUiDriverFramework.kt`
- `idea-plugin/src/ui-tests-framework/kotlin/org/jetbrains/hexana/uitestframework/HexanaEditorUiTestProbe.kt`
- `idea-plugin/src/ui-tests-framework/kotlin/org/jetbrains/hexana/uitestframework/HexanaRunConfigurationUiTestProbe.kt`
- `HexanaTeamCityArtifacts.kt`
- `HexanaTeamCityIdeDownloader.kt`

## Framework Rules

- Use Driver workflow helpers for IDE-level actions: launching Starter contexts, opening files, waiting for editor tabs,
  reading standard IDE UI such as console output, and closing the IDE.
- Use the Hexana-domain DSL entry points from `framework/` in tests:
  - lifecycle and context setup through `hexanaIde(...)`, `HexanaIdeConfiguration`, and `HexanaUiDriverTestSupport`;
  - editor workflows through `HexanaIde.hexanaEditor(...)`, `HexanaEditor`, and `HexanaEditorExpectations`;
  - settings workflows through `HexanaIde.hexanaSettings(...)`;
  - run/debug workflows through `HexanaIde.runWasm(...)`, `HexanaIde.debugWasm(...)`, `HexanaIde.runNodeJsWasm(...)`,
    `HexanaIde.debugNodeJsWasm(...)`, `HexanaIde.debugWasmWithBreakpoint(...)`, and
    `HexanaIde.debugNodeJsWasmWithBreakpoint(...)`;
  - paused-session debugger workflows through `HexanaIde.withWasmRunDsl(...)` plus
    `startNodeJsDebugPausedAtBreakpoint(...)` / `startWasmtimeDebugPausedAtBreakpoint(...)`,
    `selectWasmDebuggerTab()`, `awaitWasmDebuggerTabState(...)`,
    `stepInWasmDebuggerAndAwaitNewHighlight(...)`, and `stopWasmDebugSession()`;
  - packaged MCP workflows through `HexanaIde.withGlobalMcpClient { ... }`.
- Run/debug migration is complete at the visible test surface. Keep any low-level run/debug setup behind
  `HexanaRunConfigurationUiTestProbe` or a focused helper in `src/test/kotlin/org/jetbrains/hexana/ui/framework/`,
  but the test body itself should stay on the `HexanaIde` entry points above.
- Use plugin-side `@Remote` probes for Hexana Compose editor state that Driver cannot observe through stable Swing
  selectors. The packaged `HexanaEditorUiTestProbe` reports provider kind, Hexana tabs, search state, cursor offset, and
  WAT conversion state.
- Keep Hexana-specific remote helpers in the packaged UI-test framework boundary. `HexanaRunConfigurationUiTestProbe`
  may call product APIs to create run configurations, launch debug sessions, and inspect debugger state. The
  breakpoint-inspection helper `org.jetbrains.hexana.run.WasmRunConfigTestHelper` also lives in
  `idea-plugin/src/ui-tests-framework/kotlin` so the packaged plugin ZIP carries the remote helper boundary.
- Do not call `utility(...)`, `singleProject()`, IDE services, Run Content Manager, or MCP transport plumbing directly
  from normal tests. Put that logic behind a focused helper in `src/test/kotlin/org/jetbrains/hexana/ui/framework/`.
- Verify `HexanaRunConfigurationDriverTest` does not contain any of the legacy run/debug Driver symbols with:
  `rtk rg -n "runIdeWithDriver|useDriverAndCloseIde|singleProject\\(|utility\\(|getRunContentManager|@Remote|ConsoleViewImpl|launchWasmtime|launchNodeJs|DebugTestHelperRef|NodeJsDebugTestHelperRef|WasmRunConfigTestHelper|Thread\\.sleep\\(3_000\\)|openFile\\(" ui-driver-tests/src/test/kotlin/org/jetbrains/hexana/ui/HexanaRunConfigurationDriverTest.kt`
  Any match means the visible test surface still leaks the old path.
- Keep the UI-first boundary clear: Driver owns user workflows when stable controls exist; probes observe product state
  or perform narrow unavoidable setup for flows such as run/debug and MCP that cannot be driven reliably through UI.
- For debugger flows this means: selecting the "Wasm" debugger tab goes through Driver, because only the production
  `ContentManagerListener` flips stepping to instruction granularity. Session control (start-paused, step, resume, stop)
  goes through `WasmRunConfigTestHelper`, which calls the same `XDebugSession` entry points as the toolbar actions.
- Packaged probes intentionally live under `idea-plugin/src/ui-tests-framework/kotlin` and ship in the plugin ZIP used by
  these tests. Keep them tiny, assertion-oriented, and meaningful as product state rather than test-only behavior.
- Add Compose accessibility names only for durable user-facing controls that Driver must locate or activate. Do not add
  names for internal layout nodes or virtualized table content just to make assertions possible.
- Do not test behavior with fixed coordinates, pixel colors, screenshots, row heights, font metrics, or exact scroll
  positions. Prefer standard Driver selectors, accessibility names for stable controls, and plugin probes for product
  state.
- Keep new probes small and assertion-oriented; avoid implementing new runtime behavior for tests.

## When To Touch This Module

- Plugin installation flow changed.
- Plugin packaging or ZIP layout changed.
- Sandbox IDE startup behavior changed.
- End-to-end file-opening behavior changed in ways unit tests cannot cover.
- Run configuration or execution behavior changed for Wasm engines.

Do not use this module as the default validation path for normal parser or editor changes.

## Run Command

- Compile without launching an IDE: `./gradlew :ui-driver-tests:compileTestKotlin`
- Full UI-driver suite, matching TeamCity aggregation:
  `HEXANA_USE_LOCAL_PLUGIN=true HEXANA_LOCAL_PLUGIN_ZIP=/path/to/hexana.zip ./gradlew :ui-driver-tests:teamcityUiDriverTests`
- Focused local runs:
  - `./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaWasmProjectDriverTest`
  - `./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaRunConfigurationDriverTest`
  - `./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaWasmDebuggerTabDriverTest`
  - `./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaEditorSurfaceSmokeTest`
  - `./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaSettingsSmokeTest`
  - `./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaMcpPackagedSmokeTest`

## TeamCity Pickup

`teamcityUiDriverTests` in `ui-driver-tests/build.gradle.kts` depends on the normal Gradle `test` task. A new JUnit 5
test class under `ui-driver-tests/src/test/kotlin` is picked up by the aggregator automatically, so no TeamCity DSL
change is needed unless the build needs new inputs.

## Required Environment Configuration

Exactly one plugin source mode must be enabled:

- `HEXANA_USE_LOCAL_PLUGIN=true` together with `HEXANA_LOCAL_PLUGIN_ZIP=/path/to/plugin.zip`
- `HEXANA_DOWNLOAD_PLUGIN=true`

If both are enabled or neither is enabled, the test is expected to fail fast.

Other inputs:

- `HEXANA_PLUGIN_BUILD_NUMBER`: optional TeamCity build number when downloading a plugin
- `-PtestIdeType=<type>` or `HEXANA_IDE_TYPE`: IDE product to launch. Supported values include
  `IntellijIdea`, `WebStorm`, `CLion`, and `PyCharm`. Defaults to `IntellijIdea`.
- `-PtestIdeVersion=<version>` or `HEXANA_IDE_VERSION`: public IDE version to download.
- `HEXANA_IDE_BUILD_NUMBER`: public IDE build number to download. Set only one of `HEXANA_IDE_VERSION` or
  `HEXANA_IDE_BUILD_NUMBER`.
- `HEXANA_IDE_BUILD_TYPE`: TeamCity IDE installer build type used when neither public IDE version nor public build
  number is set. Defaults by selected IDE product:
  `IdeInstallers_Idea_Installers`, `IdeInstallers_WebStorm_Installers`,
  `IdeInstallers_PyCharm_Installers`, or `IdeInstallers_CIDR_CLion_Installers`.
- TeamCity auth if guest access is insufficient:
  - `TEAMCITY_USERNAME` and `TEAMCITY_PASSWORD`
  - or JVM properties `teamcity.user` and `teamcity.token`
  - or `teamcity.local.properties` in the repo root, optionally redirected with `HEXANA_TEAMCITY_PROPERTIES`

## Practical Rules

- Treat these tests as networked integration tests, not unit tests.
- Expect slow execution and external failure modes.
- Prefer running them only after narrower module tests are already green.
- Use `./gradlew :ui-driver-tests:compileTestKotlin` as the baseline validation for framework or documentation-adjacent
  changes that include code examples or API references.
- Run focused or full UI-driver tests only when the change affects packaged IDE behavior and exactly one plugin source
  mode is configured. Full `teamcityUiDriverTests` is appropriate for plugin packaging, IDE startup, installation flow,
  MCP packaging, or end-to-end UI behavior changes.
- If you change the plugin ZIP source logic, update the README in this module too.

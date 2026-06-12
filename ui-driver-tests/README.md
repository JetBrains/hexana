# Hexana Driver UI Tests

This module contains end-to-end UI tests for the Hexana IntelliJ plugin using JetBrains IDE Starter + Driver.

## What It Does

- Downloads IDEA Ultimate installer from TeamCity.
- Resolves the Hexana plugin — either from a local ZIP or by downloading from TeamCity (see configuration below).
- Installs the plugin into test IDE.
- Opens a local wasm project and verifies `hello.wasm` can be opened.
- Runs Wasm configurations with Wasmtime: core modules (`_start`), exported functions (`--invoke`), and component models.
- Runs a packaged-plugin MCP smoke through the framework MCP DSL and checks that Hexana tools are exposed through the
  real IDE MCP server.

## Test Framework

Use the shared Driver workflow helpers in `src/test/kotlin/org/jetbrains/hexana/ui/framework/HexanaUiDriverFramework.kt`
for normal IDE workflows:

- `hexanaIde(...)` creates the Starter context, launches the IDE with Driver, waits for IDE indicators, records the
  screen, and closes the IDE.
- Use `hexanaIde(..., configure = { ... })` for lifecycle setup such as run and indicator timeouts, raw context patches,
  global MCP enablement, and analytics wiring. Plugin source resolution stays inside `HexanaUiDriverTestSupport`.
- `HexanaIde` owns workflow-level actions such as opening a project file and waiting for editor tabs.
- `HexanaEditor` owns editor actions and state reads such as `snapshot()`, `searchState()`, and `watState()`.
- `HexanaEditorExpectations`, reached through `editor.expect { ... }`, owns common JUnit assertions such as
  `providerKind(...)`, `tabsContain(...)`, and `tabsDoNotContain(...)`.
- Settings tests should use `HexanaIde.hexanaSettings(...)` for standard settings-dialog navigation and assertions.
- `HexanaRunConfigurationDriverTest` intentionally remains on its existing direct Driver/helper path in this branch.
  Do not migrate run/debug tests into the framework until that work is explicitly scoped.
- Packaged MCP tests should use `HexanaIde.withGlobalMcpClient { ... }`. The helper owns IDE MCP server URL resolution,
  transport fallback, project-path headers, and connection retry, while tests keep the real MCP client calls visible.

Tests should not call `driver.utility(...)`, framework `@Remote` interfaces, `singleProject()`, IDE services, or run
content plumbing for normal Hexana workflows. Add or extend a framework helper instead, then keep the test body focused
on the user-visible behavior or the product state being asserted. Direct low-level Driver calls are still appropriate
inside framework files and in one-off experiments while designing a stable helper.

Use plugin-side `@Remote` probes when Driver cannot observe a stable Swing component tree. Hexana's editor surface is
mostly Compose and virtualized content, so tests use the hybrid approach:

- Driver still owns the user workflow: launch IDE, open files, wait for tabs, read console text from standard IDE UI.
- `HexanaEditorUiTestProbe` is packaged into the plugin from `idea-plugin/src/ui-tests-framework/kotlin` and is exposed
  to tests through `HexanaEditorUiTestProbeRef`.
- The probe reports product-level state such as provider kind, available Hexana tabs, cursor offset, search state, and
  WAT conversion state.
- The MCP smoke test verifies Hexana through the real MCP client path while the framework owns the IDE service and
  transport details.

Keep probe methods small and assertion-oriented. They should expose state that is already meaningful to the product
instead of recreating UI rendering checks in the test process.

## Extending The Framework

Start from the public test DSL, not from remote helpers:

- Lifecycle setup belongs in `hexanaIde(...)`, `HexanaIdeConfiguration`, or `HexanaUiDriverTestSupport` when it affects
  Starter context creation, IDE launch timeouts, global MCP enablement, analytics, plugin ZIP resolution, or IDE
  artifact resolution.
- Editor workflows belong in `HexanaIde` and `HexanaEditor`. Keep Driver in charge of stable user actions, and keep raw
  product state available through typed reads like `snapshot()`, `searchState()`, and `watState()`.
- Common editor assertions belong in `HexanaEditorExpectations`. Prefer the typed concepts `HexanaProviderKind` and
  `HexanaEditorTab`; string fallbacks are acceptable for dynamic tabs such as `.debug_*`.
- MCP workflows belong in `HexanaMcpDsl.kt` and should enter through `HexanaIde.withGlobalMcpClient { ... }`.
- Packaged probes belong under `idea-plugin/src/ui-tests-framework/kotlin`. They are intentionally included in the
  plugin ZIP so the tests validate the packaged artifact; keep them tiny and limited to state observation or narrow
  unavoidable setup that cannot be driven reliably through UI.

## Compose Accessibility Names

Add a Compose accessibility name when a test needs to locate or activate a real user-facing control through Driver and
there is no stable existing IDE component or action for it. Good candidates are named buttons, toggles, fields, or menu
items that represent durable user workflows.

Do not add accessibility names just to make internal Compose layout nodes observable, to expose table data, or to assert
virtualized rendering details. For Hexana's Compose editor panels, prefer a plugin-side `@Remote` probe for product
state and keep Driver interaction at workflow boundaries.

## What Not To Test With Coordinates Or Pixels

Do not test Hexana behavior by clicking fixed screen coordinates or asserting pixels, screenshots, row heights, scroll
offsets, colors, font metrics, or exact virtualized table positions. These are fragile across IDE builds, platforms,
themes, scaling, font settings, and Compose implementation details.

Prefer stable Driver selectors for standard IDE chrome, accessibility names for durable product controls, and
plugin-side probes for Hexana editor state.

## Run

From repository root, compile the UI-driver tests without launching an IDE:

```bash
./gradlew :ui-driver-tests:compileTestKotlin
```

Run the full UI-driver suite through the same Gradle aggregator used by TeamCity:

```bash
HEXANA_USE_LOCAL_PLUGIN=true HEXANA_LOCAL_PLUGIN_ZIP=/path/to/hexana.zip \
  ./gradlew :ui-driver-tests:teamcityUiDriverTests
```

Useful focused runs:

```bash
./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaWasmProjectDriverTest

./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaRunConfigurationDriverTest

./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaEditorSurfaceSmokeTest

./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaSettingsSmokeTest

./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.framework.HexanaUiDriverFrameworkSmokeTest

./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaMcpPackagedSmokeTest
```

Focused run/debug UI tests are optional for normal code review because they launch a packaged IDE and require one plugin
source mode to be configured, for example `HEXANA_USE_LOCAL_PLUGIN=true HEXANA_LOCAL_PLUGIN_ZIP=/path/to/hexana.zip`.
When validating run/debug behavior specifically, run:

```bash
HEXANA_USE_LOCAL_PLUGIN=true HEXANA_LOCAL_PLUGIN_ZIP=/path/to/hexana.zip \
  ./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaRunConfigurationDriverTest
```

## TeamCity Pickup

`ui-driver-tests/build.gradle.kts` registers `teamcityUiDriverTests`, which depends on the module's normal `test` task.
The Hexana TeamCity `UI Driver Tests` build runs that aggregator when it is present. To add a UI-driver test for CI,
put a JUnit 5 test class under `ui-driver-tests/src/test/kotlin`, use the normal `@Test` methods, and keep the existing
`@Tag("hexana")` convention. No TeamCity DSL change is needed for a new class in this module unless the build itself
needs new environment variables or artifacts.

### Plugin source (mutually exclusive — exactly one must be enabled)

| Variable | Default | Description |
|---|---|---|
| `HEXANA_USE_LOCAL_PLUGIN` | disabled | Use a local plugin ZIP. Requires `HEXANA_LOCAL_PLUGIN_ZIP` to be set. |
| `HEXANA_DOWNLOAD_PLUGIN` | disabled | Download the plugin from TeamCity (`InnoHub_HexFlow_PublishIdeaPluginNightly`). |

Truthy values: `true`, `1`, `yes` (case-insensitive). If both are enabled or neither is enabled, the test fails with a clear error.

| Variable | Default | Description |
|---|---|---|
| `HEXANA_LOCAL_PLUGIN_ZIP` | — | Absolute or relative path to a locally built plugin ZIP. Required when `HEXANA_USE_LOCAL_PLUGIN` is enabled. |
| `HEXANA_PLUGIN_BUILD_NUMBER` | latest | When downloading: target a specific TeamCity build number instead of the latest successful build. |

Examples:

```bash
# Use a local plugin
HEXANA_USE_LOCAL_PLUGIN=true HEXANA_LOCAL_PLUGIN_ZIP=/path/to/hexana.zip \
  ./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaWasmProjectDriverTest

# Download latest plugin from TeamCity
HEXANA_DOWNLOAD_PLUGIN=true \
  ./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaWasmProjectDriverTest

# Download plugin from a specific build
HEXANA_DOWNLOAD_PLUGIN=true HEXANA_PLUGIN_BUILD_NUMBER=123 \
  ./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaWasmProjectDriverTest
```

### Other environment variables

- `HEXANA_IDE_BUILD_TYPE` (default: `IdeInstallers_Idea_Installers`)
- TeamCity credentials (used when guest auth cannot access a private build configuration):
  - `TEAMCITY_USERNAME` + `TEAMCITY_PASSWORD`
  - or Java system properties `teamcity.user` + `teamcity.token`
  - or local file `teamcity.local.properties` in project root (path can be overridden with `HEXANA_TEAMCITY_PROPERTIES`):
    - `teamcity.user=...`
    - `teamcity.token=...`

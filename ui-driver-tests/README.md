# Hexana Driver UI Tests (Experimental)

This module contains the first migrated UI tests from RemoteRobot to the new IDE Starter + Driver framework.

## What it does

- Downloads IDEA Ultimate installer from TeamCity.
- Resolves the Hexana plugin — either from a local ZIP or by downloading from TeamCity (see configuration below).
- Installs the plugin into test IDE.
- Opens a local wasm project and verifies `hello.wasm` can be opened.
- Runs a packaged-plugin MCP smoke against the real IDE `/stream` endpoint and checks that Hexana tools are exposed
  there.

## Run

From repository root:

```bash
./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaWasmProjectDriverTest

./gradlew :ui-driver-tests:test --tests org.jetbrains.hexana.ui.HexanaMcpPackagedSmokeTest
```

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

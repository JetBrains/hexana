package org.jetbrains.hexana.ui

import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.installer.StandardInstaller
import com.intellij.ide.starter.junit5.newContext
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.util.common.logOutput
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.minutes

@Tag("hexana")
class HexanaWasmProjectDriverTest {
  @Test
  fun openWasmProject() {
    val projectDir = resolveProjectDir()
    val pluginZip = resolvePluginZip()

    val ideInfo = IdeProductProvider.IU.copy(
      buildType = HexanaTeamCityArtifacts.resolveIdeBuildType(),
      buildNumber = "",
      tag = null,
      getInstaller = { StandardInstaller(HexanaTeamCityIdeDownloader) }
    )

    val context = Starter.newContext(
      ideInfo = ideInfo,
      testName = "hexana-open-wasm-project"
    ) {
      project = LocalProjectInfo(projectDir = projectDir, isReusable = true)
    }.apply {
      pluginConfigurator.installPluginFromPath(pluginZip)
    }

    context.runIdeWithDriver(runTimeout = 15.minutes) { withScreenRecording() }
      .useDriverAndCloseIde {
        waitForIndicators(5.minutes)
        openFile(wasmFileName, waitForCodeAnalysis = false, isTextEditor = false)

        ideFrame {
          editorTabs {
            tab(wasmFileName, fullMatch = false).waitFound()
          }
        }
      }
  }

  private fun resolveProjectDir(): Path =
    Path.of("testData", "projects", projectName)
      .toAbsolutePath().normalize()

  private fun resolvePluginZip(): Path {
    val useLocal = isTruthy(System.getenv(useLocalPluginEnv))
    val download = isTruthy(System.getenv(downloadPluginEnv))

    if (useLocal && download) {
      error("Both $useLocalPluginEnv and $downloadPluginEnv are enabled. Enable exactly one.")
    }
    if (!useLocal && !download) {
      error(
        "Neither $useLocalPluginEnv nor $downloadPluginEnv is enabled. Enable exactly one: " +
            "set $useLocalPluginEnv=true with $localPluginZipEnv pointing to a plugin zip, " +
            "or set $downloadPluginEnv=true to download from TeamCity."
      )
    }

    if (useLocal) {
      val localPluginZipPath = System.getenv(localPluginZipEnv)?.trim().orEmpty()
      require(localPluginZipPath.isNotBlank()) {
        "$useLocalPluginEnv is enabled but $localPluginZipEnv is not set or blank."
      }
      val pluginZip = Path.of(localPluginZipPath).toAbsolutePath().normalize()
      require(pluginZip.exists()) {
        "Local plugin zip from '$localPluginZipEnv' does not exist: $pluginZip"
      }
      logOutput("Using local Hexana plugin ZIP from '$localPluginZipEnv': $pluginZip")
      return pluginZip
    }

    val buildNumber = System.getenv(pluginBuildNumberEnv)?.trim()?.ifBlank { null }
    return HexanaTeamCityArtifacts.downloadHexanaPluginZip(buildNumber)
  }

  private fun isTruthy(value: String?): Boolean {
    return value?.trim()?.lowercase() in setOf("true", "1", "yes")
  }

  companion object {
    private const val projectName = "hexana_wasm_project"
    private const val wasmFileName = "hello.wasm"
    private const val localPluginZipEnv = "HEXANA_LOCAL_PLUGIN_ZIP"
    private const val useLocalPluginEnv = "HEXANA_USE_LOCAL_PLUGIN"
    private const val downloadPluginEnv = "HEXANA_DOWNLOAD_PLUGIN"
    private const val pluginBuildNumberEnv = "HEXANA_PLUGIN_BUILD_NUMBER"
  }
}

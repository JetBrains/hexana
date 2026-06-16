package org.jetbrains.hexana.ui

import com.intellij.ide.starter.community.PublicIdeDownloader
import com.intellij.ide.starter.community.model.BuildType
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.IdeProductProvider
import com.intellij.ide.starter.ide.installer.StandardInstaller
import com.intellij.ide.starter.junit5.newContext
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.util.common.logOutput
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

internal object HexanaUiDriverTestSupport {
    const val projectName = "hexana_wasm_project"
    const val wasmFileName = "hello.wasm"
    const val defaultMcpPort = 64342

    private const val localPluginZipEnv = "HEXANA_LOCAL_PLUGIN_ZIP"
    private const val useLocalPluginEnv = "HEXANA_USE_LOCAL_PLUGIN"
    private const val downloadPluginEnv = "HEXANA_DOWNLOAD_PLUGIN"
    private const val pluginBuildNumberEnv = "HEXANA_PLUGIN_BUILD_NUMBER"
    private const val ideTypeSystemProperty = "hexana.testIdeType"
    private const val ideTypeEnv = "HEXANA_IDE_TYPE"
    private const val ideVersionSystemProperty = "hexana.testIdeVersion"
    private const val ideVersionEnv = "HEXANA_IDE_VERSION"
    private const val ideBuildNumberEnv = "HEXANA_IDE_BUILD_NUMBER"
    private const val postHogHostSystemProperty = "hexana.posthog.host"
    private const val postHogFlushAtSystemProperty = "hexana.posthog.flushAt"
    private const val postHogFlushIntervalMsSystemProperty = "hexana.posthog.flushIntervalMs"

    private val ideProduct = resolveIdeProduct()
    private val ideVersion = resolveConfigValue(ideVersionSystemProperty, ideVersionEnv)
    private val ideBuildNumber = System.getenv(ideBuildNumberEnv)?.trim()?.ifBlank { null }

    fun newContext(testName: String): IDETestContext {
        val context = Starter.newContext(
            ideInfo = resolveIdeInfo(),
            testName = testName,
        ) {
            project = LocalProjectInfo(projectDir = resolveProjectDir(), isReusable = true)
        }
        context.pluginConfigurator.installPluginFromPath(resolvePluginZip())
        return context
    }

    fun enableGlobalMcpServer(context: IDETestContext, port: Int = defaultMcpPort): IDETestContext {
        val optionsDir = context.paths.configDir.resolve("options").createDirectories()
        optionsDir.resolve("mcpServer.xml").writeText(
            """
      <application>
        <component name="McpServerSettings">
          <option name="enableMcpServer" value="true" />
          <option name="mcpServerPort" value="$port" />
        </component>
      </application>
      """.trimIndent()
        )
        return context
    }

    fun configurePostHog(context: IDETestContext, server: FakePostHogServer): IDETestContext =
        context.applyVMOptionsPatch {
            addSystemProperty(postHogHostSystemProperty, server.url)
            addSystemProperty(postHogFlushAtSystemProperty, 1)
            addSystemProperty(postHogFlushIntervalMsSystemProperty, 1_000)
        }

    fun configureSendAnalytics(context: IDETestContext, enabled: Boolean): IDETestContext {
        val optionsDir = context.paths.configDir.resolve("options").createDirectories()
        optionsDir.resolve("hexana.xml").writeText(
            """
      <application>
        <component name="Hexana">
          <option name="sendAnalytics" value="$enabled" />
        </component>
      </application>
      """.trimIndent()
        )
        return context
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

    private fun resolveIdeInfo(): IdeInfo = when {
        ideVersion != null && ideBuildNumber != null -> {
            error("Set only one of $ideVersionEnv or $ideBuildNumberEnv.")
        }

        ideVersion != null -> {
            ideInfo(
                buildType = BuildType.RELEASE.type,
                buildNumber = "",
                version = ideVersion,
                getInstaller = { StandardInstaller(PublicIdeDownloader()) }
            )
        }

        ideBuildNumber != null -> {
            ideInfo(
                buildType = "",
                buildNumber = ideBuildNumber,
                version = "",
                getInstaller = { StandardInstaller(PublicIdeDownloader()) }
            )
        }

        else -> {
            ideInfo(
                buildType = HexanaTeamCityArtifacts.resolveIdeBuildType(ideProduct),
                buildNumber = "",
                version = "",
                getInstaller = { StandardInstaller(HexanaTeamCityIdeDownloader) }
            )
        }
    }

    private fun ideInfo(
        buildType: String,
        buildNumber: String,
        version: String,
        getInstaller: (IdeInfo) -> StandardInstaller,
    ): IdeInfo = ideProduct.copy(
        buildType = buildType,
        buildNumber = buildNumber,
        version = version,
        tag = null,
        getInstaller = getInstaller
    )

    private fun resolveIdeProduct(): IdeInfo {
        val ideType = resolveConfigValue(ideTypeSystemProperty, ideTypeEnv) ?: "IntellijIdea"
        return when (ideType.normalizedIdeType()) {
            "intellijidea", "intellijideaultimate", "idea", "iu" -> IdeProductProvider.IU
            "webstorm", "ws" -> IdeProductProvider.WS
            "clion", "cl" -> IdeProductProvider.CL
            "pycharm", "pycharmprofessional", "python", "py" -> IdeProductProvider.PY
            else -> error(
                "Unsupported IDE type '$ideType'. Set $ideTypeSystemProperty or $ideTypeEnv to one of: " +
                        "IntellijIdea, WebStorm, CLion, PyCharm."
            )
        }
    }

    private fun resolveConfigValue(systemPropertyName: String, environmentVariableName: String): String? =
        System.getProperty(systemPropertyName)?.trim()?.ifBlank { null }
            ?: System.getenv(environmentVariableName)?.trim()?.ifBlank { null }

    private fun String.normalizedIdeType(): String =
        filter { it.isLetterOrDigit() }.lowercase()
}

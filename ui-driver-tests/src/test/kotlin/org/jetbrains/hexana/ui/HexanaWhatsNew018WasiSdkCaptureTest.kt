package org.jetbrains.hexana.ui

import com.intellij.driver.client.Driver
import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.cleanupWasiSdkForWhatsNewCapture
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.seedWasiSdkForWhatsNewCapture
import org.jetbrains.hexana.ui.framework.webAssemblySettings
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

@Tag("screenshot")
class HexanaWhatsNew018WasiSdkCaptureTest {
    @Test
    fun captureManagedWasiSdkSelector() {
        val isolatedHome = Path.of("/tmp/hx018").toAbsolutePath().normalize()
        require(!isolatedHome.exists()) { "Capture home already exists: $isolatedHome" }
        isolatedHome.createDirectories()
        try {
            hexanaIde(
                testName = "hexana-whats-new-018-managed-wasi-sdk-capture",
                configure = {
                    showWhatsNewOnNewVersion(false)
                    isolatedUserHomeForCapture(isolatedHome)
                },
            ) {
                maximizeWindow()
                closeProjectToolWindow()
                var seeded = false
                try {
                    val state = driver.seedWasiSdkForWhatsNewCapture(WASI_SDK_VERSION).associatePayload()
                    seeded = true
                    require(state["sdkVersion"] == WASI_SDK_VERSION.toString()) { "Unexpected seed state: $state" }
                    require(state["installedVersions"] == WASI_SDK_VERSION.toString()) { "Unexpected seed state: $state" }
                    require(state["activeVersion"] == WASI_SDK_VERSION.toString()) { "Unexpected seed state: $state" }
                    require(state["lldbPathSetting"].isNullOrEmpty()) { "Expected an empty explicit LLDB setting: $state" }
                    val storageRoot = Path.of(state.getValue("storageRoot")).toAbsolutePath().normalize()
                    require(storageRoot.startsWith(isolatedHome)) {
                        "wasi-sdk capture storage escaped the isolated home: home=$isolatedHome root=$storageRoot"
                    }

                    webAssemblySettings {
                        openWasiSdkSelectorForCapture()
                        copyScreenshot(driver, "managed-wasi-sdk", "managed-wasi-sdk.png")
                    }
                } finally {
                    if (seeded) driver.cleanupWasiSdkForWhatsNewCapture(WASI_SDK_VERSION)
                }
            }
        } finally {
            isolatedHome.toFile().deleteRecursively()
        }
    }

    private fun List<String>.associatePayload(): Map<String, String> = associate { entry ->
        val separator = entry.indexOf('=')
        require(separator > 0) { "Malformed capture payload entry: $entry" }
        entry.substring(0, separator) to entry.substring(separator + 1)
    }

    private fun copyScreenshot(driver: Driver, screenshotName: String, outputFileName: String) {
        val screenshot = driver.takeScreenshot(screenshotName)
            ?: error("Driver did not return a screenshot path for $screenshotName")
        val source = Path.of(screenshot.toString()).toAbsolutePath().normalize()
        require(source.exists()) { "Driver screenshot path does not exist: $source" }
        val target = repoRoot().resolve("docs/whatsnew/captures/0.18").createDirectories().resolve(outputFileName)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        logOutput("Hexana What's New 0.18 screenshot: $target")
    }

    private fun repoRoot(): Path {
        var path = Path.of("").toAbsolutePath().normalize()
        while (!path.resolve("settings.gradle.kts").exists()) {
            path = path.parent ?: error("Could not locate repository root")
        }
        return path
    }

    private companion object {
        const val WASI_SDK_VERSION = 33
    }
}

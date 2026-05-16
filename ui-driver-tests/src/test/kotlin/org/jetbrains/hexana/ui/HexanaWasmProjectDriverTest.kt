package org.jetbrains.hexana.ui

import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

@Tag("hexana")
class HexanaWasmProjectDriverTest {
  @Test
  fun openWasmProject() {
    val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-open-wasm-project")
    context.openWasmProjectAndWaitForEditor()
  }

  @Test
  fun openWasmProjectSendsAnalyticsWhenEnabled() {
    FakePostHogServer.start().use { server ->
      val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-open-wasm-project-analytics-enabled")
        .let { HexanaUiDriverTestSupport.configurePostHog(it, server) }
        .let { HexanaUiDriverTestSupport.configureSendAnalytics(it, enabled = true) }

      context.openWasmProjectAndWaitForEditor {
        server.awaitEvent("wasm_file_opened")
          .requireDistinctId()
          .assertProperty("surface", "ide_plugin")
          .assertPositiveNumberProperty("file_size_bytes")
      }
    }
  }

  @Test
  fun openWasmProjectDoesNotSendAnalyticsWhenDisabled() {
    FakePostHogServer.start().use { server ->
      val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-open-wasm-project-analytics-disabled")
        .let { HexanaUiDriverTestSupport.configurePostHog(it, server) }
        .let { HexanaUiDriverTestSupport.configureSendAnalytics(it, enabled = false) }

      context.openWasmProjectAndWaitForEditor {
        server.assertNoEvents(Duration.ofSeconds(3))
      }
    }
  }

  private fun IDETestContext.openWasmProjectAndWaitForEditor(afterOpen: () -> Unit = {}) {
    runIdeWithDriver(runTimeout = 15.minutes) { withScreenRecording() }
      .useDriverAndCloseIde {
        waitForIndicators(5.minutes)
        openFile(HexanaUiDriverTestSupport.wasmFileName, waitForCodeAnalysis = false, isTextEditor = false)

        ideFrame {
          editorTabs {
            tab(HexanaUiDriverTestSupport.wasmFileName, fullMatch = false).waitFound()
          }
        }

        afterOpen()
      }
  }
}

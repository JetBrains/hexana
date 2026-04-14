package org.jetbrains.hexana.ui

import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

@Tag("hexana")
class HexanaWasmProjectDriverTest {
  @Test
  fun openWasmProject() {
    val context = HexanaUiDriverTestSupport.newContext(testName = "hexana-open-wasm-project")

    context.runIdeWithDriver(runTimeout = 15.minutes) { withScreenRecording() }
      .useDriverAndCloseIde {
        waitForIndicators(5.minutes)
        openFile(HexanaUiDriverTestSupport.wasmFileName, waitForCodeAnalysis = false, isTextEditor = false)

        ideFrame {
          editorTabs {
            tab(HexanaUiDriverTestSupport.wasmFileName, fullMatch = false).waitFound()
          }
        }
      }
  }
}

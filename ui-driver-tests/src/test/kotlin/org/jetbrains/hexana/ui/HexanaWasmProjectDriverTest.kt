package org.jetbrains.hexana.ui

import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Duration

@Tag("hexana")
class HexanaWasmProjectDriverTest {
  @Test
  fun openWasmProject() {
    hexanaIde(testName = "hexana-open-wasm-project") {
      hexanaEditor(HexanaUiDriverTestSupport.wasmFileName)
    }
  }

  @Test
  fun openWasmProjectSendsAnalyticsWhenEnabled() {
    FakePostHogServer.start().use { server ->
      hexanaIde(
        testName = "hexana-open-wasm-project-analytics-enabled",
        configure = { analytics(server, enabled = true) },
      ) {
        hexanaEditor(HexanaUiDriverTestSupport.wasmFileName)
        server.awaitEvent("binary_opened")
          .requireDistinctId()
          .assertProperty("format", "wasm")
          .assertProperty("surface", "ide_plugin")
          .assertPositiveNumberProperty("file_size_bytes")
      }
    }
  }

  @Test
  fun openWasmProjectDoesNotSendAnalyticsWhenDisabled() {
    FakePostHogServer.start().use { server ->
      hexanaIde(
        testName = "hexana-open-wasm-project-analytics-disabled",
        configure = { analytics(server, enabled = false) },
      ) {
        hexanaEditor(HexanaUiDriverTestSupport.wasmFileName)
        server.assertNoEvents(Duration.ofSeconds(3))
      }
    }
  }
}

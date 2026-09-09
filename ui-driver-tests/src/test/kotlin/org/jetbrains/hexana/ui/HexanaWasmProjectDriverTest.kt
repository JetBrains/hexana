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

  /**
   * The plugin's most common session is "open the IDE, look at one binary,
   * quit". With production flush settings that single event never reaches the
   * batch threshold, so it only arrives if the IDE flushes on exit.
   */
  @Test
  fun openWasmProjectFlushesAnalyticsOnIdeShutdown() {
    FakePostHogServer.start().use { server ->
      hexanaIde(
        testName = "hexana-open-wasm-project-analytics-shutdown-flush",
        configure = { analyticsWithProductionFlush(server, enabled = true) },
      ) {
        hexanaEditor(HexanaUiDriverTestSupport.wasmFileName)
        // Still queued: neither flushAt (20) nor the 30s timer has fired.
        server.assertNoEvents(Duration.ofSeconds(3))
      }

      // useDriverAndCloseIde has shut the IDE down by now. Without the
      // shutdown flush the queued event would have died with the process.
      server.awaitEvent("binary_opened")
        .requireDistinctId()
        .assertProperty("format", "wasm")
        .assertProperty("surface", "ide_plugin")
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

package org.jetbrains.hexana.ui

import org.jetbrains.hexana.ui.framework.HexanaEditorTab
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Regression check for EDT responsiveness while the Functions tab of a large
 * module resolves. The module-wide inputs (functions/type parse, call graph +
 * dominators for unreachable badges) used to run at first cell render on the
 * EDT and froze the whole IDE; they now materialize during background tab
 * resolution in the enricher.
 *
 * Requires a large real-world module and is skipped when none is present.
 * Point HEXANA_LARGE_WASM at a big .wasm to run (default: the local USD
 * wasm64 build).
 */
@Tag("hexana")
class HexanaLargeWasmResponsivenessTest {

  @Test
  fun functionsTabOfLargeModuleDoesNotBlockEdt() {
    val largeWasm = Path.of(
      System.getenv("HEXANA_LARGE_WASM")
        ?: (System.getProperty("user.home") + "/IdeaProjects/USD/build-wasm64/bin/usdviewweb.wasm"),
    )
    assumeTrue(Files.exists(largeWasm)) { "Large wasm fixture not found: $largeWasm" }

    hexanaIde(testName = "hexana-large-wasm-responsiveness") {
      val editor = openAbsoluteFile(largeWasm)
      editor.selectTab(HexanaEditorTab.FUNCTIONS.tabName)

      // Sample EDT round-trips while background tab resolution runs. Each
      // snapshot() call hops through the EDT inside the probe, so a blocked
      // EDT shows up as a long call.
      val clock = TimeSource.Monotonic
      val samplingWindow = 2.minutes
      val start = clock.markNow()
      var maxStallMs = 0L
      var samples = 0
      while (start.elapsedNow() < samplingWindow) {
        val callStart = clock.markNow()
        val snapshot = editor.snapshot()
        val elapsedMs = callStart.elapsedNow().inWholeMilliseconds
        if (elapsedMs > maxStallMs) maxStallMs = elapsedMs
        samples++
        assertTrue(
          HexanaEditorTab.FUNCTIONS.tabName in snapshot.panelNames,
          "Functions tab disappeared; tabs: ${snapshot.panelNames}",
        )
        Thread.sleep(250)
      }
      println("EDT responsiveness during resolution: $samples snapshots, max stall ${maxStallMs}ms")

      // Re-select and snapshot after the resolution window: the rendered
      // table's cell providers must stay cheap too.
      editor.selectTab(HexanaEditorTab.FUNCTIONS.tabName)
      val finalCall = clock.markNow()
      val finalSnapshot = editor.snapshot()
      val finalMs = finalCall.elapsedNow().inWholeMilliseconds
      println("EDT responsiveness after resolution: snapshot took ${finalMs}ms")

      assertEquals(HexanaEditorTab.FUNCTIONS.tabName, finalSnapshot.selectedTabName)
      assertTrue(maxStallMs < 5_000, "EDT stalled for ${maxStallMs}ms during Functions tab resolution")
      assertTrue(finalMs < 5_000, "EDT stalled for ${finalMs}ms after Functions tab resolution")
    }
  }
}
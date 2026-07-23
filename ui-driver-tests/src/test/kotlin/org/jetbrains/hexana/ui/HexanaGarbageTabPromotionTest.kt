package org.jetbrains.hexana.ui

import org.jetbrains.hexana.ui.framework.HexanaEditorTab
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Functional checks for Garbage tab-group promotion, split out of
 * [HexanaLargeWasmResponsivenessTest] so they run against small modules whose
 * analysis resolves in seconds instead of racing a large module's background
 * resolution on slow agents.
 *
 * Both directions of the promotion rule are covered:
 *  - hello_wasi.wasm yields garbage analysis with only the Data subtab (its
 *    one defined function is exported and reachable, all types are referenced,
 *    and it has a single data segment), so the advertised "Garbage"
 *    placeholder must be replaced by a promoted "Garbage: <subtab>" panel.
 *  - hello.wasm yields garbage in several categories (unreachable functions,
 *    unreferenced types, data segments), so the group must stay a bare
 *    "Garbage" tab and must not promote.
 */
@Tag("hexana")
class HexanaGarbageTabPromotionTest {

  @Test
  fun garbagePlaceholderPromotesToSingleSubtabPanel() {
    hexanaIde(testName = "hexana-garbage-tab-promotion") {
      val editor = openWasm("hello_wasi.wasm")

      val clock = TimeSource.Monotonic
      val deadline = 2.minutes
      val start = clock.markNow()
      var tabs: List<String> = emptyList()
      var promoted = false
      var resolved = false
      while (start.elapsedNow() < deadline) {
        val snapshot = editor.snapshot()
        tabs = snapshot.panelNames
        promoted = HexanaEditorTab.GARBAGE.presentIn(tabs) && tabs.none { it == HexanaEditorTab.GARBAGE.tabName }
        resolved = snapshot.panelsResolved
        // Resolution finishing without the promoted panel is a definitive
        // failure; no point waiting out the rest of the deadline.
        if (promoted || resolved) break
        Thread.sleep(250)
      }
      assertTrue(
        promoted,
        "Garbage group did not promote to a single-subtab panel (resolutionComplete=$resolved) within $deadline; tabs: $tabs",
      )
    }
  }

  @Test
  fun garbageGroupWithMultipleSubtabsStaysGrouped() {
    hexanaIde(testName = "hexana-garbage-tab-grouped") {
      val editor = openWasm()

      val clock = TimeSource.Monotonic
      val deadline = 2.minutes
      val start = clock.markNow()
      var tabs: List<String> = emptyList()
      var resolved = false
      while (start.elapsedNow() < deadline) {
        val snapshot = editor.snapshot()
        tabs = snapshot.panelNames
        resolved = snapshot.panelsResolved
        if (resolved) break
        Thread.sleep(250)
      }
      assertTrue(resolved, "Background tab resolution did not finish within $deadline; tabs: $tabs")
      assertTrue(
        tabs.any { it == HexanaEditorTab.GARBAGE.tabName },
        "Expected the bare Garbage group tab for a module with garbage in several categories; tabs: $tabs",
      )
      assertTrue(
        tabs.none { it != HexanaEditorTab.GARBAGE.tabName && HexanaEditorTab.GARBAGE.matches(it) },
        "Garbage group with several subtabs must not promote; tabs: $tabs",
      )
    }
  }
}
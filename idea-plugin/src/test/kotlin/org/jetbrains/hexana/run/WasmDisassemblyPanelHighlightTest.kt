package org.jetbrains.hexana.run

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Unit tests for the "Wasm" debugger tab presentation: the highlight that
 * [WasmDisassemblyPanel.highlight] moves to the paused instruction, and the
 * [WasmDebuggerTabState] read that the packaged UI-driver probe reports.
 *
 * The end-to-end flow is covered by `HexanaWasmDebuggerTabDriverTest` in
 * `ui-driver-tests`; these tests pin the selection rules without an IDE launch.
 *
 * Swing components are created here, so the test needs a platform application
 * and runs on the EDT.
 */
class WasmDisassemblyPanelHighlightTest : BasePlatformTestCase() {

    private fun panelWith(vararg rows: Pair<Int, String>): WasmDisassemblyPanel {
        val model = WasmDisassemblyTableModel()
        model.update(rows.map { (offset, instruction) -> WasmDisassemblyTableModel.Row(offset, instruction) })
        return WasmDisassemblyPanel(model)
    }

    fun testExactOffsetWins() {
        val panel = panelWith(0x10 to "local.get 0", 0x14 to "local.get 1", 0x18 to "i32.add")

        panel.highlight(0x14)

        assertEquals(1, panel.table.selectedRow)
    }

    fun testNearestOffsetBelowIsUsedWhenNoRowMatchesExactly() {
        val panel = panelWith(0x10 to "local.get 0", 0x14 to "local.get 1", 0x18 to "i32.add")

        // 0x16 is inside the 0x14 instruction's operand bytes.
        panel.highlight(0x16)

        assertEquals(1, panel.table.selectedRow)
    }

    fun testRowsWithoutRecordedOffsetAreSkipped() {
        val panel = panelWith(-1 to "  end", 0x20 to "i32.add")

        // The only row at or below 0x1f has no recorded offset, so nothing is highlighted.
        panel.highlight(0x1f)

        assertEquals(-1, panel.table.selectedRow)
    }

    fun testBlockEndRowIsSkippedByTheFallbackPass() {
        val panel = panelWith(0x10 to "block", 0x12 to "  local.get 0", -1 to "  end", 0x20 to "i32.add")

        panel.highlight(0x1c)

        assertEquals(1, panel.table.selectedRow)
    }

    fun testOffsetBeforeEveryRowHighlightsNothing() {
        val panel = panelWith(0x10 to "local.get 0", 0x14 to "i32.add")

        panel.highlight(0x08)

        assertEquals(-1, panel.table.selectedRow)
    }

    fun testTabStateReportsTheHighlightedInstruction() {
        val panel = panelWith(0x10 to "local.get 0", 0x14 to "local.get 1", 0x18 to "i32.add")
        val stackModel = WasmStackTableModel().apply {
            update(
                listOf(
                    WasmStackTableModel.Row(0, "i32", "3"),
                    WasmStackTableModel.Row(1, "i32", "7"),
                ),
            )
        }
        panel.highlight(0x18)

        val state = wasmDebuggerTabStateOf(
            sessionSuspended = true,
            tabRegistered = true,
            tabSelected = true,
            disassemblyPanel = panel,
            stackModel = stackModel,
        )

        assertTrue(state.sessionActive)
        assertTrue(state.sessionSuspended)
        assertTrue(state.tabRegistered)
        assertTrue(state.tabSelected)
        assertEquals(3, state.disassemblyRowCount)
        assertEquals(2, state.selectedRow)
        assertEquals(0x18, state.selectedOffset)
        assertEquals("i32.add", state.selectedInstruction)
        assertEquals(2, state.operandStackRowCount)
    }

    fun testTabStateWithoutHighlightReportsNoInstruction() {
        val panel = panelWith(0x10 to "local.get 0")

        val state = wasmDebuggerTabStateOf(
            sessionSuspended = false,
            tabRegistered = true,
            tabSelected = false,
            disassemblyPanel = panel,
            stackModel = WasmStackTableModel(),
        )

        assertEquals(-1, state.selectedRow)
        assertEquals(-1, state.selectedOffset)
        assertEquals("", state.selectedInstruction)
        assertEquals(0, state.operandStackRowCount)
    }

    fun testClearedModelDropsTheHighlight() {
        val panel = panelWith(0x10 to "local.get 0", 0x14 to "i32.add")
        panel.highlight(0x14)
        assertEquals(1, panel.table.selectedRow)

        panel.model.clear()

        val state = wasmDebuggerTabStateOf(
            sessionSuspended = false,
            tabRegistered = true,
            tabSelected = true,
            disassemblyPanel = panel,
            stackModel = WasmStackTableModel(),
        )
        assertEquals(0, state.disassemblyRowCount)
        assertEquals(-1, state.selectedOffset)
        assertEquals("", state.selectedInstruction)
    }
}

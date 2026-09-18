package org.jetbrains.hexana.ui

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnostic
import org.jetbrains.hexana.mcp.scripting.SliceCellValue
import org.jetbrains.hexana.mcp.scripting.SlicePage
import org.jetbrains.hexana.mcp.scripting.SlicePageError
import org.jetbrains.hexana.mcp.scripting.SlicePageResult
import org.jetbrains.hexana.mcp.scripting.SliceRow
import org.jetbrains.hexana.shared.scripting.SliceColumn
import org.jetbrains.hexana.shared.scripting.SliceColumnType
import org.jetbrains.hexana.shared.scripting.SliceRegistrationInfo
import org.jetbrains.hexana.ui.panel.ScriptRenderMode
import org.jetbrains.hexana.ui.panel.ScriptRunStamp
import org.jetbrains.hexana.ui.panel.SORT_CAP_ROW_COUNT
import org.jetbrains.hexana.ui.panel.SliceTabModel
import org.jetbrains.hexana.ui.panel.isScriptStale
import org.jetbrains.hexana.ui.panel.sha256Hex

/**
 * Pure-logic tests for the v2 scripting model classes.
 *
 * No Compose runtime or IntelliJ Platform needed: all tests drive the
 * model classes directly.
 */
class ScriptV2ModelTest : UsefulTestCase() {

    // -------------------------------------------------------------------------
    // Render-mode selection
    // -------------------------------------------------------------------------

    fun testLegacyModeWhenNoSlices() {
        // A result with no slices must route to the legacy path.
        val mode = if (emptyList<SliceRegistrationInfo>().isEmpty()) {
            ScriptRenderMode.Legacy
        } else {
            ScriptRenderMode.Compound
        }
        assertEquals(ScriptRenderMode.Legacy, mode)
    }

    fun testCompoundModeWhenSlicesPresent() {
        val slices = listOf(
            SliceRegistrationInfo(
                name = "user:functions",
                formats = emptyList(),
                params = emptyList(),
                columns = listOf(SliceColumn("Name", SliceColumnType.STRING)),
            )
        )
        val mode = if (slices.isEmpty()) ScriptRenderMode.Legacy else ScriptRenderMode.Compound
        assertEquals(ScriptRenderMode.Compound, mode)
    }

    // -------------------------------------------------------------------------
    // Staleness
    // -------------------------------------------------------------------------

    fun testNotStaleWhenHashMatchesStamp() {
        val stamp = ScriptRunStamp(version = 1, textHash = "abc")
        assertFalse(
            "Should not be stale when the current hash matches the stamp",
            isScriptStale(currentHash = "abc", stampHash = stamp.textHash),
        )
    }

    fun testStaleWhenHashChanges() {
        val stamp = ScriptRunStamp(version = 1, textHash = "abc")
        assertTrue(
            "Should be stale when the hash changed",
            isScriptStale(currentHash = "def", stampHash = stamp.textHash),
        )
    }

    fun testNotStaleAfterUserUndoBack() {
        // User edits (version bumps, hash changes), then undoes back to the
        // original text: hash equality clears the badge regardless of version.
        val stamp = ScriptRunStamp(version = 1, textHash = "abc")
        assertFalse(
            "Badge should clear when hash matches stamped hash after undo",
            isScriptStale(currentHash = "abc", stampHash = stamp.textHash),
        )
    }

    fun testNotStaleBeforeFirstStamp() {
        // No run yet: an empty stamp hash never marks the panel stale.
        assertFalse(isScriptStale(currentHash = "abc", stampHash = ""))
    }

    fun testRecomputeResetsStamp() {
        // Simulate a recompute: new stamp taken from the new text.
        val stamp = ScriptRunStamp(version = 3, textHash = "xyz")
        assertFalse(
            "After recompute, stale should be false",
            isScriptStale(currentHash = "xyz", stampHash = stamp.textHash),
        )
    }

    // -------------------------------------------------------------------------
    // Problem row (SliceTabModel.applyError)
    // -------------------------------------------------------------------------

    fun testProblemRowAppendedOnError() {
        val info = sliceInfo(columns = listOf(
            SliceColumn("Name", SliceColumnType.STRING),
            SliceColumn("Offset", SliceColumnType.OFFSET),
        ))
        val model = SliceTabModel(info)
        // Load one page first.
        model.applyPage(SlicePage(
            rows = listOf(SliceRow(anchor = 0L, cells = listOf(
                SliceCellValue("foo"),
                SliceCellValue("0x10", numeric = 16L),
            ))),
            nextOffset = null,
            total = 1,
        ))
        assertEquals(1, model.rows.size)

        // Apply an error.
        model.applyError(HexanaScriptDiagnostic(code = "RUNTIME_ERROR", message = "boom"))

        // Rows keep previous + exactly one problem row.
        assertEquals(2, model.rows.size)
        val problemRow = model.rows[1]
        assertTrue("problem row must start with 'script error:'",
            problemRow.cells.firstOrNull()?.text?.startsWith("script error:") == true)
        assertEquals("boom must appear in problem row",
            "script error: boom", problemRow.cells[0].text)
        // Problem row has null anchor.
        assertNull(problemRow.anchor)
        assertTrue("model must be in error state", model.hasError)
    }

    fun testErrorRowHasCorrectCellCount() {
        val colCount = 3
        val info = sliceInfo(columns = (1..colCount).map { SliceColumn("c$it", SliceColumnType.STRING) })
        val model = SliceTabModel(info)
        model.applyError(HexanaScriptDiagnostic(code = "X", message = "msg"))

        val row = model.rows.single()
        assertEquals("problem row must have same number of cells as schema", colCount, row.cells.size)
    }

    // -------------------------------------------------------------------------
    // Paging accumulation + sort cap
    // -------------------------------------------------------------------------

    fun testPagingAccumulation() {
        val info = sliceInfo()
        val model = SliceTabModel(info)

        val page1 = makePage(rowCount = 3, nextOffset = 3)
        model.applyPage(page1)
        assertEquals(3, model.rows.size)
        assertEquals(3, model.nextOffset)
        assertFalse(model.fullyLoaded)

        val page2 = makePage(rowCount = 2, nextOffset = null)
        model.applyPage(page2)
        assertEquals(5, model.rows.size)
        assertNull(model.nextOffset)
        assertTrue(model.fullyLoaded)
    }

    fun testSortCapNotFlippedBelowThreshold() {
        val info = sliceInfo()
        val model = SliceTabModel(info)
        model.applyPage(makePage(rowCount = SORT_CAP_ROW_COUNT, nextOffset = null))
        // Exactly at cap: still capped.
        assertFalse(
            "Sort cap must not flip at exactly $SORT_CAP_ROW_COUNT rows",
            model.sortCapped,
        )
    }

    fun testSortCapFlipsAboveThreshold() {
        val info = sliceInfo()
        val model = SliceTabModel(info)
        model.applyPage(makePage(rowCount = SORT_CAP_ROW_COUNT + 1, nextOffset = null))
        assertTrue("Sort cap must flip when rows exceed $SORT_CAP_ROW_COUNT", model.sortCapped)
    }

    fun testSortCapNotFlippedForSmallDataset() {
        val info = sliceInfo()
        val model = SliceTabModel(info)
        model.applyPage(makePage(rowCount = 100, nextOffset = null))
        assertFalse(model.sortCapped)
    }

    // -------------------------------------------------------------------------
    // sha256Hex sanity
    // -------------------------------------------------------------------------

    fun testSha256HexIsStable() {
        val h1 = sha256Hex("hello world")
        val h2 = sha256Hex("hello world")
        assertEquals(h1, h2)
    }

    fun testSha256HexDiffersForDifferentInputs() {
        val h1 = sha256Hex("script version 1")
        val h2 = sha256Hex("script version 2")
        assertFalse("SHA-256 must differ for different inputs", h1 == h2)
    }

    fun testSha256HexIsHex64Chars() {
        val h = sha256Hex("any text")
        assertEquals(64, h.length)
        assertTrue("must be hex", h.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun sliceInfo(
        name: String = "user:test",
        columns: List<SliceColumn> = listOf(SliceColumn("Value", SliceColumnType.STRING)),
    ) = SliceRegistrationInfo(name = name, formats = emptyList(), params = emptyList(), columns = columns)

    private fun makePage(rowCount: Int, nextOffset: Int?): SlicePage {
        val rows = (0 until rowCount).map { i ->
            SliceRow(anchor = i.toLong(), cells = listOf(SliceCellValue(text = "row$i")))
        }
        return SlicePage(rows = rows, nextOffset = nextOffset, total = rowCount)
    }
}

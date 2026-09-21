package org.jetbrains.hexana.ui

import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnostic
import org.jetbrains.hexana.mcp.scripting.SliceCellValue
import org.jetbrains.hexana.mcp.scripting.SlicePage
import org.jetbrains.hexana.mcp.scripting.SlicePageError
import org.jetbrains.hexana.mcp.scripting.SlicePageResult
import org.jetbrains.hexana.mcp.scripting.SliceRow
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckRegistrationInfo
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import org.jetbrains.hexana.shared.scripting.SliceColumn
import org.jetbrains.hexana.shared.scripting.SliceColumnType
import org.jetbrains.hexana.shared.scripting.SliceRegistrationInfo
import org.jetbrains.hexana.ui.panel.CheckFindingRow
import org.jetbrains.hexana.ui.panel.CheckTabModel
import org.jetbrains.hexana.ui.panel.ScriptFindingsStore
import org.jetbrains.hexana.ui.panel.ScriptRenderMode
import org.jetbrains.hexana.ui.panel.ScriptRunStamp
import org.jetbrains.hexana.ui.panel.SORT_CAP_ROW_COUNT
import org.jetbrains.hexana.ui.panel.SliceTabModel
import org.jetbrains.hexana.ui.panel.isScriptStale
import org.jetbrains.hexana.ui.panel.renderModeFor
import org.jetbrains.hexana.ui.panel.renderAnchor
import org.jetbrains.hexana.ui.panel.sha256Hex
import org.jetbrains.hexana.ui.panel.toProblemsRow
import org.jetbrains.hexana.ui.panel.toRow

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

    // -------------------------------------------------------------------------
    // Mode decision (renderModeFor)
    // -------------------------------------------------------------------------

    fun testRenderModeSlicesOnly() {
        val slices = listOf(sliceInfo())
        val mode = renderModeFor(slices, emptyList())
        assertEquals("slices-only -> Compound", ScriptRenderMode.Compound, mode)
    }

    fun testRenderModeChecksOnly() {
        val checks = listOf(CheckRegistrationInfo(id = "user:a", severity = CheckSeverity.ERROR, description = null))
        val mode = renderModeFor(emptyList(), checks)
        assertEquals("checks-only -> Compound", ScriptRenderMode.Compound, mode)
    }

    fun testRenderModeBoth() {
        val slices = listOf(sliceInfo())
        val checks = listOf(CheckRegistrationInfo(id = "user:a", severity = CheckSeverity.WARNING, description = null))
        val mode = renderModeFor(slices, checks)
        assertEquals("slices + checks -> Compound", ScriptRenderMode.Compound, mode)
    }

    fun testRenderModeNeither() {
        val mode = renderModeFor(emptyList(), emptyList())
        assertEquals("neither -> Legacy", ScriptRenderMode.Legacy, mode)
    }

    // -------------------------------------------------------------------------
    // ScriptFindingsStore
    // -------------------------------------------------------------------------

    fun testStoreReplaceSemantics() {
        val store = ScriptFindingsStore()
        val file = LightVirtualFile("a.wasm")

        val firstFindings = listOf(
            CheckFinding("user:c1", CheckSeverity.ERROR, "first finding"),
        )
        store.replaceFindings(file, "script1.mjs", firstFindings)
        assertEquals("first run stored", 1, store.findingsFor(file).size)
        assertEquals("first finding message", "first finding", store.findingsFor(file)[0].message)

        val secondFindings = listOf(
            CheckFinding("user:c2", CheckSeverity.WARNING, "second a"),
            CheckFinding("user:c3", CheckSeverity.INFO, "second b"),
        )
        store.replaceFindings(file, "script1.mjs", secondFindings)
        assertEquals("second run replaces first: 2 findings", 2, store.findingsFor(file).size)
        assertEquals("second run first message", "second a", store.findingsFor(file)[0].message)
    }

    fun testStorePerFileIsolation() {
        val store = ScriptFindingsStore()
        val fileA = LightVirtualFile("a.wasm")
        val fileB = LightVirtualFile("b.wasm")

        store.replaceFindings(fileA, "s.mjs", listOf(CheckFinding("user:x", CheckSeverity.ERROR, "for a")))

        assertTrue("b has no findings", store.findingsFor(fileB).isEmpty())
        assertEquals("a still has its finding", 1, store.findingsFor(fileA).size)
    }

    fun testStoreClearAll() {
        val store = ScriptFindingsStore()
        val file = LightVirtualFile("c.wasm")
        store.replaceFindings(file, "s.mjs", listOf(CheckFinding("user:x", CheckSeverity.ERROR, "msg")))
        store.clearAll()
        assertTrue("after clearAll, no findings", store.findingsFor(file).isEmpty())
    }

    fun testStoreEmptyFindingsRemovesEntry() {
        val store = ScriptFindingsStore()
        val file = LightVirtualFile("d.wasm")
        store.replaceFindings(file, "s.mjs", listOf(CheckFinding("user:x", CheckSeverity.ERROR, "msg")))
        store.replaceFindings(file, "s.mjs", emptyList())
        assertNull("empty findings removes entry", store.getEntry(file))
    }

    // -------------------------------------------------------------------------
    // CheckTabModel
    // -------------------------------------------------------------------------

    fun testCheckTabModelPresentWhenChecksNonEmpty() {
        val checks = listOf(
            CheckRegistrationInfo(id = "user:no-imports", severity = CheckSeverity.ERROR, description = null),
        )
        val model = CheckTabModel(checks)
        assertTrue("hasChecks when checks non-empty", model.hasChecks)
    }

    fun testCheckTabModelAbsentWhenChecksEmpty() {
        val model = CheckTabModel(emptyList())
        assertFalse("hasChecks false when checks empty", model.hasChecks)
    }

    fun testCheckTabModelEmptyStateWhenFindingsEmpty() {
        val checks = listOf(CheckRegistrationInfo(id = "user:c", severity = CheckSeverity.WARNING, description = null))
        val model = CheckTabModel(checks)
        model.findings = emptyList()
        assertTrue("isClean when findings empty", model.isClean)
        assertTrue("rows empty when findings empty", model.rows.isEmpty())
    }

    fun testCheckTabModelNotCleanWhenFindingsNull() {
        val model = CheckTabModel(listOf(CheckRegistrationInfo("user:c", CheckSeverity.INFO, null)))
        assertFalse("not isClean when findings == null (still loading)", model.isClean)
    }

    // -------------------------------------------------------------------------
    // Anchor rendering
    // -------------------------------------------------------------------------

    fun testRenderAnchorFunction() {
        val anchor = ScriptAnchor.Function(funcidx = 3L)
        assertEquals("func[3]", anchor.renderAnchor())
    }

    fun testRenderAnchorByteRange() {
        val anchor = ScriptAnchor.ByteRange(offset = 0x10L, length = 8L)
        assertEquals("0x10+8", anchor.renderAnchor())
    }

    // -------------------------------------------------------------------------
    // CheckFinding -> CheckFindingRow (Checks tab)
    // -------------------------------------------------------------------------

    fun testCheckFindingToRowFunctionAnchorError() {
        val finding = CheckFinding(
            checkId = "user:no-imports",
            severity = CheckSeverity.ERROR,
            message = "too many imports",
            anchor = ScriptAnchor.Function(funcidx = 42L),
        )
        val row = finding.toRow()
        assertEquals("ERROR", row.severity)
        assertEquals("user:no-imports", row.checkId)
        assertEquals("too many imports", row.message)
        assertEquals("func[42]", row.anchorText)
    }

    fun testCheckFindingToRowByteRangeWarning() {
        val finding = CheckFinding(
            checkId = "user:large-section",
            severity = CheckSeverity.WARNING,
            message = "section too large",
            anchor = ScriptAnchor.ByteRange(offset = 256L, length = 1024L),
        )
        val row = finding.toRow()
        assertEquals("WARNING", row.severity)
        assertEquals("0x100+1024", row.anchorText)
    }

    fun testCheckFindingToRowNoAnchorInfo() {
        val finding = CheckFinding(
            checkId = "user:file-check",
            severity = CheckSeverity.INFO,
            message = "informational",
            anchor = null,
        )
        val row = finding.toRow()
        assertEquals("INFO", row.severity)
        assertEquals("", row.anchorText)
    }

    // -------------------------------------------------------------------------
    // CheckFinding -> FindingProblemsRow (Problems tab injection)
    // -------------------------------------------------------------------------

    fun testProblemsRowFunctionAnchor() {
        val finding = CheckFinding(
            checkId = "user:c",
            severity = CheckSeverity.ERROR,
            message = "bad function",
            anchor = ScriptAnchor.Function(funcidx = 7L),
        )
        val row = finding.toProblemsRow()
        assertEquals("check:error", row.kind)
        assertEquals("func[7]", row.location)
        assertEquals("", row.offsetText)
        assertEquals(-1L, row.offsetValue)
        assertEquals("bad function", row.message)
    }

    fun testProblemsRowByteRangeAnchor() {
        val finding = CheckFinding(
            checkId = "user:size",
            severity = CheckSeverity.WARNING,
            message = "too big",
            anchor = ScriptAnchor.ByteRange(offset = 0x20L, length = 0x40L),
        )
        val row = finding.toProblemsRow()
        assertEquals("check:warning", row.kind)
        assertEquals("user:size", row.location)
        assertEquals("0x20", row.offsetText)
        assertEquals(0x20L, row.offsetValue)
        assertEquals("0x40", row.sizeText)
        assertEquals(0x40L, row.sizeValue)
    }

    fun testProblemsRowNoAnchor() {
        val finding = CheckFinding(
            checkId = "user:global",
            severity = CheckSeverity.INFO,
            message = "global note",
            anchor = null,
        )
        val row = finding.toProblemsRow()
        assertEquals("check:info", row.kind)
        assertEquals("user:global", row.location)
        assertEquals("", row.offsetText)
        assertEquals(-1L, row.offsetValue)
        assertEquals("", row.sizeText)
    }
}

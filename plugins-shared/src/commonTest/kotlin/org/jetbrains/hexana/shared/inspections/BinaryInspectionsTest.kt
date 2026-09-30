package org.jetbrains.hexana.shared.inspections

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryFormat
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.BinaryProblemsTableTabsProvider
import org.jetbrains.hexana.shared.COMPATIBILITY_TAB_NAME
import org.jetbrains.hexana.shared.PROBLEMS_TAB_NAME
import org.jetbrains.hexana.shared.ProblemMenuItem
import org.jetbrains.hexana.shared.ProblemNavigator
import org.jetbrains.hexana.shared.RowSelectionData
import org.jetbrains.hexana.shared.TableTabsContext
import org.jetbrains.hexana.shared.WasmBinaryWriter
import org.jetbrains.hexana.shared.features.RuntimeTarget
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.jetbrains.hexana.shared.features.WasmFeatureCatalog
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility
import org.jetbrains.hexana.shared.problemsTableTab
import org.jetbrains.hexana.shared.relatedTabName
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryInspectionsTest {

    // ---------------------------------------------------------------------
    // Wasm parse problems
    // ---------------------------------------------------------------------

    /** invalid/binary.63.wasm: the type section declares two functypes but holds one. */
    private fun truncatedTypeSection() = WasmBinaryProvider(
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
            0x01, 0x07, 0x02,
            0x60, 0x00, 0x00,
        ).toCommonByteBuffer(),
    )

    @Test
    fun `an unknown type byte is a warning, located in its type entry`() {
        val problem = inspectBinary(truncatedTypeSection()).single()

        assertEquals(WasmParseProblemsInspection.id, problem.inspectionId)
        assertEquals("wasm.unknown_type_byte", problem.code)
        assertEquals(ProblemSeverity.Warning, problem.severity)
        assertEquals("Unknown type byte", problem.kind)
        assertNotNull(problem.byteRange)
    }

    @Test
    fun `a clean module reports nothing but still advertises the tab`() {
        val provider = moduleUsingSignExtension()

        assertEquals(emptyList(), inspectBinary(provider))
        assertTrue(mayHaveProblems(provider), "the code section is a possible problem source")
        assertNull(BinaryProblemsTableTabsProvider().singleTab(PROBLEMS_TAB_NAME, provider, TableTabsContext()))
    }

    // ---------------------------------------------------------------------
    // Native parser diagnostics
    // ---------------------------------------------------------------------

    @Test
    fun `a fatal ELF parse failure surfaces as an error`() {
        val problem = inspectBinary(zeroedElf()).single()

        assertEquals(NativeParseDiagnosticsInspection.id, problem.inspectionId)
        assertEquals("elf.invalid_header", problem.code)
        assertEquals(ProblemSeverity.Error, problem.severity)
    }

    @Test
    fun `formats without an inspection are neither inspected nor advertised`() {
        val generic = GenericBinaryProvider(ByteArray(8).toCommonByteBuffer())

        assertEquals(emptyList(), inspectBinary(generic))
        assertFalse(mayHaveProblems(generic))
        assertEquals(emptyList(), BinaryProblemsTableTabsProvider().availableTabNames(generic, TableTabsContext()).first)
    }

    // ---------------------------------------------------------------------
    // Runtime compatibility
    // ---------------------------------------------------------------------

    @Test
    fun `a proposal a declared runtime predates is one warning`() {
        val context = InspectionContext(runtimeCompatibility = compatibility(RuntimeTarget("Chrome", "1")))

        val problem = inspectBinary(moduleUsingSignExtension(), context).single()

        assertEquals(WasmRuntimeCompatibilityInspection.UNSUPPORTED_CODE, problem.code)
        assertEquals(ProblemSeverity.Warning, problem.severity)
        assertTrue(problem.message.startsWith("Sign-extension"), problem.message)
        assertTrue("Chrome 1" in problem.message, problem.message)
        assertNull(problem.byteRange, "proposal detection has no byte position")
    }

    @Test
    fun `no declared runtime means no compatibility inspection`() {
        val nothingDeclared = InspectionContext(runtimeCompatibility = compatibility())

        assertEquals(emptyList(), inspectBinary(moduleUsingSignExtension(), nothingDeclared))
        assertFalse(WasmRuntimeCompatibilityInspection.mayReport(moduleUsingSignExtension(), nothingDeclared))
    }

    // ---------------------------------------------------------------------
    // Merging, ordering, failure isolation
    // ---------------------------------------------------------------------

    @Test
    fun `host problems are merged and everything is ordered by severity then offset`() {
        val info = problem(ProblemSeverity.Info, offset = 0)
        val laterError = problem(ProblemSeverity.Error, offset = 20)
        val earlierError = problem(ProblemSeverity.Error, offset = 10)
        val fileLevelError = problem(ProblemSeverity.Error, offset = null)

        val result = inspectBinary(
            GenericBinaryProvider(ByteArray(8).toCommonByteBuffer()),
            InspectionContext(hostProblems = listOf(info, laterError, fileLevelError, earlierError)),
        )

        assertEquals(listOf(earlierError, laterError, fileLevelError, info), result)
    }

    @Test
    fun `an inspection that throws becomes a warning instead of hiding the others`() {
        val broken = object : BinaryInspection {
            override val id = "test.broken"
            override val displayName = "Broken"
            override val description = "Always throws."
            override fun mayReport(provider: BinaryProvider, context: InspectionContext) = true
            override fun inspect(provider: BinaryProvider, context: InspectionContext): List<BinaryProblem> =
                error("boom")
        }

        val result = inspectBinary(
            truncatedTypeSection(),
            inspections = listOf(broken, WasmParseProblemsInspection),
        )

        val failure = result.single { it.code == INSPECTION_FAILED_CODE }
        assertEquals("test.broken", failure.inspectionId)
        assertTrue("boom" in failure.message, failure.message)
        assertTrue(result.any { it.inspectionId == WasmParseProblemsInspection.id })
    }

    @Test
    fun `script findings keep their check id, severity and anchor`() {
        val inFunction = CheckFinding("user:no-loops", CheckSeverity.ERROR, "loop found", ScriptAnchor.Function(7))
        val inBytes = CheckFinding("user:magic", CheckSeverity.INFO, "odd bytes", ScriptAnchor.ByteRange(16, 4))

        val a = inFunction.toBinaryProblem()
        assertEquals("user:no-loops", a.inspectionId)
        assertEquals(ProblemSeverity.Error, a.severity)
        assertEquals(7, a.funcIdx)
        assertEquals("func 7", a.location)
        assertNull(a.byteRange)

        val b = inBytes.toBinaryProblem()
        assertEquals(ProblemSeverity.Info, b.severity)
        assertEquals(ProblemByteRange(16, 4), b.byteRange)
        assertEquals("user:magic", b.location)
    }

    @Test
    fun `counts label errors and warnings, and notes only when alone`() {
        val mixed = listOf(
            problem(ProblemSeverity.Error, 0), problem(ProblemSeverity.Error, 1),
            problem(ProblemSeverity.Warning, 2), problem(ProblemSeverity.Info, 3),
        ).counts()
        assertEquals("2 errors, 1 warning", mixed.label)
        assertEquals(ProblemSeverity.Error, mixed.highestSeverity)

        assertEquals("1 note", listOf(problem(ProblemSeverity.Info, 0)).counts().label)
        assertEquals("", emptyList<BinaryProblem>().counts().label)
        assertNull(emptyList<BinaryProblem>().counts().highestSeverity)
    }

    // ---------------------------------------------------------------------
    // Problems tab
    // ---------------------------------------------------------------------

    @Test
    fun `the tab lists host problems even on a binary with no inspection`() {
        val finding = problem(ProblemSeverity.Warning, offset = 4)
        val context = TableTabsContext(hostProblems = listOf(finding))
        val generic = GenericBinaryProvider(ByteArray(8).toCommonByteBuffer())

        assertEquals(listOf(PROBLEMS_TAB_NAME), BinaryProblemsTableTabsProvider().availableTabNames(generic, context).first)
        val tab = assertNotNull(BinaryProblemsTableTabsProvider().singleTab(PROBLEMS_TAB_NAME, generic, context))
        assertEquals(listOf("Severity", "Kind", "Location", "Offset", "Size", "Message"), tab.columns.map { it.name })
        assertEquals("0x4", tab.columns[3].cellProvider(0).representation)
        assertEquals(4, tab.rowSelectionProvider(0)?.offset)
    }

    @Test
    fun `the navigator owns row clicks and menu items, and its absence leaves the default`() {
        val inFunction = problem(ProblemSeverity.Error, offset = 0).copy(funcIdx = 3)
        val fileLevel = problem(ProblemSeverity.Warning, offset = null)
        val opened = mutableListOf<BinaryProblem>()
        val navigator = ProblemNavigator(
            open = { opened += it },
            menuItems = listOf(ProblemMenuItem("Show function", isVisible = { it.funcIdx != null }) {}),
        )

        val tab = assertNotNull(problemsTableTab(listOf(inFunction, fileLevel), FILE_SIZE, navigator))
        tab.rowClickAction!!(0)
        assertEquals(listOf(inFunction), opened)
        assertTrue(tab.contextMenuItems.single().isVisible(0))
        assertFalse(tab.contextMenuItems.single().isVisible(1))
        assertNull(tab.rowSelectionProvider(1), "a file-level problem has no bytes to select")

        val plain = assertNotNull(problemsTableTab(listOf(inFunction), FILE_SIZE))
        assertNull(plain.rowClickAction)
        assertEquals(emptyList(), plain.contextMenuItems)
    }

    @Test
    fun `a runtime-compatibility problem opens the Compatibility tab when the host can select it`() {
        val incompatible = problem(ProblemSeverity.Warning, offset = null).copy(inspectionId = WasmRuntimeCompatibilityInspection.id)
        val other = problem(ProblemSeverity.Warning, offset = 0)
        assertEquals(COMPATIBILITY_TAB_NAME, incompatible.relatedTabName)
        assertNull(other.relatedTabName)

        val opened = mutableListOf<BinaryProblem>()
        val selected = mutableListOf<String>()
        val tab = assertNotNull(
            problemsTableTab(listOf(incompatible, other), FILE_SIZE, ProblemNavigator(open = { opened += it }, selectTab = { selected += it })),
        )
        tab.rowClickAction!!(0)
        tab.rowClickAction!!(1)
        assertEquals(listOf(COMPATIBILITY_TAB_NAME), selected)
        assertEquals(listOf(other), opened)

        val showInTab = tab.contextMenuItems.single()
        assertEquals("Show in Compatibility tab", showInTab.labelProvider(0))
        assertTrue(showInTab.isVisible(0))
        assertFalse(showInTab.isVisible(1))

        // A host that cannot select tabs keeps its own navigation for every row.
        val noTabs = assertNotNull(problemsTableTab(listOf(incompatible), FILE_SIZE, ProblemNavigator(open = { opened += it })))
        noTabs.rowClickAction!!(0)
        assertEquals(listOf(other, incompatible), opened)
        assertEquals(emptyList(), noTabs.contextMenuItems)
    }

    @Test
    fun `the errors-and-warnings filter appears only when there are notes to hide`() {
        val withNote = assertNotNull(problemsTableTab(listOf(problem(ProblemSeverity.Info, 0), problem(ProblemSeverity.Error, 1)), FILE_SIZE))
        val filter = withNote.predefinedFilters.single()
        assertFalse(filter.predicate(0))
        assertTrue(filter.predicate(1))
        assertTrue(withNote.defaultFilterShowAll, "notes are listed until the user filters them out")

        assertEquals(emptyList(), assertNotNull(problemsTableTab(listOf(problem(ProblemSeverity.Error, 0)), FILE_SIZE)).predefinedFilters)
    }

    @Test
    fun `a range becomes a selection only inside the file, clipped and at least one byte wide`() {
        assertEquals(4 to 8, ProblemByteRange(4, 8).selectionIn(16).pair())
        assertEquals(12 to 4, ProblemByteRange(12, 100).selectionIn(16).pair(), "clipped to the file end")
        assertEquals(5 to 1, ProblemByteRange(5, 0).selectionIn(16).pair(), "a point marks its byte")
        assertNull(ProblemByteRange(16, 1).selectionIn(16), "starts past the end")
        assertNull(ProblemByteRange(-1, 4).selectionIn(16), "starts before the file")
        assertNull(ProblemByteRange(Int.MAX_VALUE + 1L, 1).selectionIn(16), "would wrap in an Int")
    }

    @Test
    fun `the problems table selects no bytes for a range outside the file`() {
        val tab = assertNotNull(problemsTableTab(listOf(problem(ProblemSeverity.Error, offset = 99)), fileSize = 16))
        assertNull(tab.rowSelectionProvider(0))
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /** [RowSelectionData] has no equality; compare it through its fields. */
    private fun RowSelectionData?.pair(): Pair<Int, Int>? = this?.let { it.offset to it.size }

    private companion object {
        /** Large enough for every offset the table fixtures use. */
        const val FILE_SIZE = 1024
    }

    private fun problem(severity: ProblemSeverity, offset: Long?) = BinaryProblem(
        inspectionId = "test",
        code = "test.$severity.$offset",
        severity = severity,
        kind = "Test",
        message = "at $offset",
        byteRange = offset?.let { ProblemByteRange(it, 1) },
    )

    private fun compatibility(vararg targets: RuntimeTarget) =
        WasmRuntimeCompatibility(WasmFeatureCatalog.bundled, RuntimeTargets(targets.toList()))

    private fun zeroedElf() = ElfBinaryProvider(
        sharedBytes = ByteArray(16).toCommonByteBuffer(),
        nativeFormat = NativeBinaryFormat.Elf(
            wordSize = NativeBinaryWordSize.Bits64,
            endian = NativeBinaryEndian.Little,
            fileType = null,
            machine = null,
        ),
    )

    /** One function whose body is `i32.const 0; i32.extend8_s; drop` — the sign-extension proposal. */
    private fun moduleUsingSignExtension(): WasmBinaryProvider {
        val w = WasmBinaryWriter()
        w.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        w.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        w.writeSection(1, WasmBinaryWriter().apply {
            writeULEB128(1); writeByte(0x60); writeULEB128(0); writeULEB128(0)
        }.toByteArray())
        w.writeSection(3, WasmBinaryWriter().apply { writeULEB128(1); writeULEB128(0) }.toByteArray())
        val body = WasmBinaryWriter().apply {
            writeULEB128(0)
            writeByte(0x41); writeULEB128(0)
            writeByte(0xC0)
            writeByte(0x1A)
            writeByte(0x0B)
        }.toByteArray()
        w.writeSection(10, WasmBinaryWriter().apply {
            writeULEB128(1); writeULEB128(body.size); writeBytes(body)
        }.toByteArray())
        return WasmBinaryProvider(w.toByteArray().toCommonByteBuffer())
    }
}

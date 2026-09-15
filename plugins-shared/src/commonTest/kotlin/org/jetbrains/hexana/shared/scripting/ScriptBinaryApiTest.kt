package org.jetbrains.hexana.shared.scripting

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.api
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.arr
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.assertNonEmpty
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.call
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.callMember
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.elfProviderWithStrings
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.genericProvider
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.int
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.member
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.num
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.obj
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.str
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.string
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.stringList
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.stringOrNull
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.wasmBytes
import org.jetbrains.hexana.shared.scripting.ScriptApiTestSupport.wasmProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScriptBinaryApiTest {

    @Test
    fun identityMembersDescribeTheBinary() {
        val binary = api(wasmProvider(), "fixture.wasm")

        assertEquals("wasm", string(member(binary, "format")))
        assertEquals("Core Wasm", string(member(binary, "formatName")))
        assertEquals("fixture.wasm", string(member(binary, "name")))
        assertEquals(wasmBytes().size, int(member(binary, "size")))
    }

    @Test
    fun formatIdIsStablePerProviderKind() {
        assertEquals("wasm", string(member(api(wasmProvider()), "format")))
        assertEquals("elf", string(member(api(elfProviderWithStrings()), "format")))
        assertEquals("binary", string(member(api(genericProvider(byteArrayOf(1, 2, 3))), "format")))
    }

    @Test
    fun nameIsNullWhenTheHostDoesNotKnowIt() {
        assertEquals(ScriptValue.Null, member(api(wasmProvider()), "name"))
    }

    // -- location ------------------------------------------------------------

    private fun location(fileName: String? = null, filePath: String? = null): Triple<String?, String?, String?> {
        val binary = api(wasmProvider(), fileName, filePath)
        return Triple(
            stringOrNull(member(binary, "name")),
            stringOrNull(member(binary, "path")),
            stringOrNull(member(binary, "dir")),
        )
    }

    @Test
    fun pathAndDirComeFromTheHostSuppliedPath() {
        assertEquals(
            Triple("app.wasm", "/home/me/build/app.wasm", "/home/me/build"),
            location(filePath = "/home/me/build/app.wasm"),
        )
    }

    @Test
    fun pathAndDirAreNullWhenTheBinaryHasNoFile() {
        // A ZIP entry, a module opened from a table, anything in a browser: the
        // script has to be able to tell that there is nowhere to look, rather
        // than being handed a plausible-looking path that resolves to nothing.
        assertEquals(Triple("entry.wasm", null, null), location(fileName = "entry.wasm"))
    }

    @Test
    fun nameIsTheLastSegmentEvenWhenTheHostOnlyHasAPath() {
        // What VS Code does: its load message carries the fsPath, and it used to
        // surface that whole path as `binary.name` while the IDE and the CLI
        // reported a bare name for the same file.
        assertEquals(
            Triple("app.wasm", null, null),
            location(fileName = "/home/me/build/app.wasm"),
        )
    }

    @Test
    fun anExplicitNameWinsOverThePathsOwn() {
        // The decompressed case: VS Code shows `app.wasm` for `app.wasm.gz`, and
        // the path still points at the file that is really on disk.
        assertEquals(
            Triple("app.wasm", "/home/me/app.wasm.gz", "/home/me"),
            location(fileName = "app.wasm", filePath = "/home/me/app.wasm.gz"),
        )
    }

    @Test
    fun windowsPathsKeepTheirSeparator() {
        assertEquals(
            Triple("app.exe", "C:\\builds\\app.exe", "C:\\builds"),
            location(filePath = "C:\\builds\\app.exe"),
        )
    }

    @Test
    fun aRootLevelFileKeepsTheRootAsItsDir() {
        // Trimming the separator would leave "" on POSIX and the drive-relative
        // "C:" on Windows, which names a different directory entirely.
        assertEquals("/", location(filePath = "/app.wasm").third)
        assertEquals("C:\\", location(filePath = "C:\\app.exe").third)
    }

    @Test
    fun aBareFileNameAsAPathHasNoDir() {
        assertEquals(Triple("app.wasm", "app.wasm", null), location(filePath = "app.wasm"))
    }

    // -- bytes ---------------------------------------------------------------

    @Test
    fun bytesExposeAbsoluteAndCursorReads() {
        val bytes = member(api(genericProvider(byteArrayOf(0x00, 0x61, 0x73, 0x6D))), "bytes")

        assertEquals(4, int(member(bytes, "size")))
        assertEquals(3, int(member(bytes, "lastIndex")))
        assertEquals(0x61, int(callMember(bytes, "at", num(1))))
        // Zero-argument get() reads at the cursor and advances it, as it did
        // before this API existed.
        assertEquals(0x00, int(callMember(bytes, "get")))
        assertEquals(0x61, int(callMember(bytes, "get")))
        assertEquals(0x73, int(callMember(bytes, "get", num(2))))
    }

    @Test
    fun bytesFormatRangesAsHexAndText() {
        val bytes = member(api(genericProvider(byteArrayOf(0x48, 0x69, 0x01, 0x74, 0x68, 0x65, 0x72, 0x65))), "bytes")

        assertEquals("48 69 01 74", string(callMember(bytes, "hex", num(0), num(4))))
        assertEquals("Hi.there", string(callMember(bytes, "ascii", num(0), num(8))))

        val slice = arr(callMember(bytes, "slice", num(0), num(2)))
        assertEquals(listOf(0x48, 0x69), (0 until slice.size).map { int(slice.at(it)) })
    }

    @Test
    fun bytesReadUnsignedIntegersInEitherEndianness() {
        val bytes = member(api(genericProvider(byteArrayOf(0x01, 0x02, 0x03, 0x04))), "bytes")

        assertEquals(0x01, int(callMember(bytes, "u8", num(0))))
        assertEquals(0x0201, int(callMember(bytes, "u16", num(0))))
        assertEquals(0x0102, int(callMember(bytes, "u16", num(0), ScriptValue.Bool(false))))
        assertEquals(0x04030201, int(callMember(bytes, "u32", num(0))))
        assertEquals(0x01020304, int(callMember(bytes, "u32", num(0), ScriptValue.Bool(false))))
    }

    @Test
    fun bytesFindPatternsFromStringsAndNumberArrays() {
        val bytes = member(api(genericProvider(byteArrayOf(0x5F, 0x5F, 0x00, 0x61, 0x73, 0x6D, 0x5F, 0x5F))), "bytes")

        assertEquals(2, int(callMember(bytes, "indexOf", str("\u0000asm"))))
        assertEquals(
            2,
            int(callMember(bytes, "indexOf", ScriptValue.Arr(4) { num(byteArrayOf(0, 0x61, 0x73, 0x6D)[it].toInt()) })),
        )
        assertEquals(-1, int(callMember(bytes, "indexOf", str("nope"))))
        assertEquals(-1, int(callMember(bytes, "indexOf", str("\u0000asm"), num(3))))
    }

    @Test
    fun readsOutsideTheBinaryReportTheValidRange() {
        val bytes = member(api(genericProvider(byteArrayOf(1, 2, 3))), "bytes")

        val error = assertFailsWith<IllegalArgumentException> { callMember(bytes, "at", num(3)) }
        assertTrue(error.message!!.contains("0..2"), "message should name the valid range: ${error.message}")

        assertFailsWith<IllegalArgumentException> { callMember(bytes, "at", num(-1)) }
        assertFailsWith<IllegalArgumentException> { callMember(bytes, "hex", num(0), num(4)) }
        assertFailsWith<IllegalArgumentException> { callMember(bytes, "u32", num(1)) }
    }

    @Test
    fun oversizedRangeReadsAreRejectedBeforeAllocating() {
        val bytes = member(api(genericProvider(byteArrayOf(1, 2, 3))), "bytes")

        val error = assertFailsWith<IllegalArgumentException> {
            callMember(bytes, "hex", num(0), num((1 shl 20) + 1))
        }
        assertTrue(error.message!!.contains("chunks"), "message should suggest chunking: ${error.message}")
    }

    @Test
    fun argumentTypeAndArityErrorsNameTheFunction() {
        val bytes = member(api(genericProvider(byteArrayOf(1, 2, 3))), "bytes")

        val typeError = assertFailsWith<IllegalArgumentException> { callMember(bytes, "at", str("x")) }
        assertTrue(typeError.message!!.contains("binary.bytes.at"), typeError.message!!)

        val arityError = assertFailsWith<IllegalArgumentException> { callMember(bytes, "at") }
        assertTrue(arityError.message!!.contains("expects"), arityError.message!!)
    }

    // -- structure, strings, size -------------------------------------------

    @Test
    fun structureListsTopLevelRegionsWithOffsets() {
        val structure = assertNonEmpty(member(api(wasmProvider()), "structure"), "binary.structure")
        val first = structure.at(0)

        assertTrue(string(member(first, "name")).isNotEmpty())
        assertTrue(int(member(first, "size")) > 0)
        assertTrue(int(member(first, "offset")) >= 0)
    }

    @Test
    fun stringsCarryTheirOffsetsAndTags() {
        val strings = assertNonEmpty(member(api(elfProviderWithStrings()), "strings"), "binary.strings")
        val first = strings.at(0)

        assertEquals("hexana scripting fixture", string(member(first, "value")))
        assertEquals(0, int(member(first, "offset")))
    }

    @Test
    fun sizeAnalysisIsATreeWithPercentages() {
        val root = member(api(wasmProvider()), "sizeAnalysis")
        val total = int(member(root, "size"))
        val children = assertNonEmpty(member(root, "children"), "sizeAnalysis.children")

        val childSize = int(member(children.at(0), "size"))
        val percentage = ScriptApiTestSupport.number(
            callMember(children.at(0), "percentage", num(total))
        ).toDouble()
        assertEquals(childSize * 100.0 / total, percentage, absoluteTolerance = 0.0001)
    }

    @Test
    fun sizeAnalysisIsNullForFormatsWithoutSizeProfiling() {
        assertEquals(ScriptValue.Null, member(api(genericProvider(byteArrayOf(1, 2, 3))), "sizeAnalysis"))
    }

    // -- tabs ----------------------------------------------------------------

    @Test
    fun tabsAdvertiseTheTablesTheEditorCanShow() {
        val names = stringList(member(api(wasmProvider()), "tabs"))

        assertTrue(names.contains("Exports"), "expected an Exports tab, got $names")
        assertTrue(names.contains("Functions"), "expected a Functions tab, got $names")
    }

    @Test
    fun tabReadsCellsUsingTheColumnNamesTheEditorShows() {
        val exports = callMember(api(wasmProvider()), "tab", str("Exports"))

        assertEquals("Exports", string(member(exports, "name")))
        assertEquals(1, int(member(exports, "rowCount")))

        val columns = stringList(member(exports, "columns"))
        assertTrue(columns.contains("Name"), "expected a Name column, got $columns")

        val nameColumn = int(callMember(exports, "columnIndex", str("Name")))
        assertEquals(columns.indexOf("Name"), nameColumn)
        assertEquals("hello", string(callMember(exports, "cell", num(0), num(nameColumn))))
        assertEquals(-1, int(callMember(exports, "columnIndex", str("No Such Column"))))

        val row = stringList(callMember(exports, "row", num(0)))
        assertEquals(columns.size, row.size)
        assertTrue(row.contains("hello"))
    }

    @Test
    fun tabRowsReturnsEveryRow() {
        val exports = callMember(api(wasmProvider()), "tab", str("Exports"))
        val rows = arr(callMember(exports, "rows"))

        assertEquals(int(member(exports, "rowCount")), rows.size)
        assertTrue(stringList(rows.at(0)).contains("hello"))
    }

    @Test
    fun everyAdvertisedTabResolves() {
        // `binary.tabs` used to advertise names that `binary.tab(name)` rejected —
        // Problems and Garbage are offered whenever their sources exist, but
        // resolve to nothing when the module is clean. A loop over `binary.tabs`
        // must not throw halfway through.
        val binary = api(wasmProvider())
        val names = stringList(member(binary, "tabs"))
        assertTrue(names.isNotEmpty())

        for (name in names) {
            val tab = callMember(binary, "tab", str(name))
            assertEquals(name, string(member(tab, "name")), "tab('$name') returned a different tab")
        }
    }

    @Test
    fun anAdvertisedTabWithNoContentReadsAsAnEmptyTable() {
        val binary = api(wasmProvider())
        val names = stringList(member(binary, "tabs"))
        // The fixture module is clean, so its Problems tab has nothing to show.
        assertTrue(names.contains("Problems"), "expected the fixture to advertise Problems, got $names")

        val problems = callMember(binary, "tab", str("Problems"))

        assertEquals(0, int(member(problems, "rowCount")))
        assertEquals(emptyList(), stringList(member(problems, "columns")))
    }

    @Test
    fun unknownTabNameListsWhatIsAvailable() {
        val binary = api(wasmProvider())
        val error = assertFailsWith<IllegalArgumentException> { callMember(binary, "tab", str("Nope")) }

        assertTrue(error.message!!.contains("no such tab"), error.message!!)
        assertTrue(error.message!!.contains("Exports"), "should list the real tabs: ${error.message}")
    }

    @Test
    fun tabCellBoundsErrorsNameTheTabAndItsColumns() {
        val exports = callMember(api(wasmProvider()), "tab", str("Exports"))

        val rowError = assertFailsWith<IllegalArgumentException> {
            callMember(exports, "cell", num(99), num(0))
        }
        assertTrue(rowError.message!!.contains("Exports"), rowError.message!!)

        val columnError = assertFailsWith<IllegalArgumentException> {
            callMember(exports, "cell", num(0), num(99))
        }
        assertTrue(columnError.message!!.contains("column"), columnError.message!!)
    }

    @Test
    fun cellsAreReadOnlyWhenTheScriptAsksForThem() {
        // Laziness is the reason binary.tab() can expose a Functions table with
        // hundreds of thousands of rows: nothing is computed until a cell is read.
        var cellReads = 0
        val tab = TableTabDescriptor(
            name = "Huge",
            columns = listOf(
                ColumnDescriptor("Index") { index ->
                    cellReads++
                    CellValue(index)
                },
            ),
            rowCount = 1_000_000,
        )

        val value = scriptTabValue(tab)
        assertEquals(1_000_000, int(member(value, "rowCount")))
        assertEquals(listOf("Index"), stringList(member(value, "columns")))
        assertEquals(0, cellReads, "reading metadata must not touch any cell")

        val rows = arr(callMember(value, "rows"))
        assertEquals(1_000_000, rows.size)
        assertEquals(0, cellReads, "rows() must stay lazy")

        assertEquals("7", string(arr(rows.at(7)).at(0)))
        assertEquals(1, cellReads)
    }

    @Test
    fun cellValueExposesPrimitiveSortValuesAndDegradesForHostObjects() {
        val marker = Any()
        val tab = TableTabDescriptor(
            name = "Values",
            columns = listOf(
                ColumnDescriptor("Number") { CellValue(42, representation = "forty-two") },
                ColumnDescriptor("Object") { CellValue(marker, representation = "opaque") },
            ),
            rowCount = 1,
        )

        val value = scriptTabValue(tab)
        assertEquals("forty-two", string(callMember(value, "cell", num(0), num(0))))
        assertEquals(42, int(callMember(value, "value", num(0), num(0))))
        // A non-primitive sort key would leak an internal type into a surface
        // that is supposed to be stable, so it degrades to the displayed text.
        assertEquals("opaque", string(callMember(value, "value", num(0), num(1))))
    }

    // -- escape hatch --------------------------------------------------------

    @Test
    fun rawExposesTheUnderlyingProvider() {
        val provider: BinaryProvider = wasmProvider()
        val raw = member(api(provider), "raw")

        assertSame(provider, (raw as ScriptValue.Host).value)
    }

    @Test
    fun unknownMembersFallThroughToTheProvider() {
        // This is what keeps scripts written against the pre-v1 surface working:
        // `binary.exports` is not curated, so it resolves against the provider.
        val provider: BinaryProvider = wasmProvider()
        val binary = api(provider)

        assertSame(provider, binary.hostFallback)
        assertTrue("exports" !in obj(binary).members, "'exports' must stay a fall-through member")
    }

    @Test
    fun curatedNamesNeverShadowAProviderMemberSilently() {
        // Provider members that the curated surface would reshape are deliberately
        // left out; `structure` and `tab*` are the curated equivalents.
        val curated = obj(api(wasmProvider())).members.keys
        for (reserved in listOf("sections", "functions", "imports", "exports", "header", "symbols")) {
            assertTrue(
                reserved !in curated,
                "'$reserved' exists on providers and must not be claimed by the curated surface",
            )
        }
    }
}

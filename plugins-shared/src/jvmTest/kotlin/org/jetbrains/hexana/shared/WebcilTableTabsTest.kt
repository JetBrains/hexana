package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** CLR tabs on a Blazor WebAssembly assembly: `sample-clr.exe` converted to Webcil (`src/clr/webcil`). */
class WebcilTableTabsTest {
    private fun provider(name: String): BinaryProvider {
        val file = File("../parsing/native-fixtures/test-data/pe/$name").also { assertTrue(it.exists(), "missing fixture ${it.absolutePath}") }
        return createBinaryProvider(file.readBytes().toCommonByteBuffer(), file.name)
    }

    private val webcil = assertIs<WasmBinaryProvider>(provider("sample-clr.webcil.wasm"))
    private val pe = provider("sample-clr.exe")
    private val tabsProvider = WasmTableTabsProvider()

    private fun TableTabDescriptor.cell(row: Int, column: String): String =
        columns.single { it.name == column }.cellProvider(row).representation

    @Test
    fun `Webcil module lists the CLR tabs before the Wasm ones`() {
        val names = tabsProvider.tableTabs(webcil).map { it.name }
        assertEquals(listOf(".NET", "Types", "Methods", "Assembly Refs", "Data"), names.take(5))
        assertEquals(
            listOf("Streams", "Tables", "Type Refs", "Member Refs", "User Strings", "Resources"),
            tabsProvider.groupedTabs(webcil).single { it.name == ".NET Metadata" }.innerTabs.map { it.name },
        )
        assertEquals("Core Wasm (.NET Webcil)", fileTypeDescription(webcil))
        assertEquals("wasm", binaryFormatId(webcil))
    }

    @Test
    fun `tabs show the same assembly as the PE file`() {
        val native = NativeTableTabsProvider()
        for (name in listOf("Types", "Methods", "Assembly Refs")) {
            val expected = assertNotNull(native.singleTab(name, pe, TableTabsContext()), name)
            val actual = assertNotNull(tabsProvider.singleTab(name, webcil, TableTabsContext()), name)
            assertEquals(expected.rowCount, actual.rowCount, name)
            val column = expected.columns.first().name
            assertEquals((0 until expected.rowCount).map { expected.cell(it, column) }, (0 until actual.rowCount).map { actual.cell(it, column) }, name)
        }
        val overview = assertNotNull(tabsProvider.singleTab(".NET", webcil, TableTabsContext()))
        val rows = (0 until overview.rowCount).associate { overview.cell(it, overview.columns[0].name) to overview.cell(it, overview.columns[1].name) }
        assertEquals("Webcil 0.0 in a Wasm wrapper (browser-wasm)", rows["Container"])
        assertEquals("sample-clr", rows["Assembly"])
        assertTrue("Platform" !in rows)
    }

    @Test
    fun `cheap tab discovery agrees with the full tab list`() {
        val context = TableTabsContext()
        val (ordinary, grouped) = assertNotNull(tabsProvider.availableTabNames(webcil, context))
        val full = tabsProvider.tableTabs(webcil, context)
        assertEquals(full.map { it.name }, ordinary.filter { advertised -> full.any { it.name == advertised } })
        for (tab in full) {
            assertEquals(tab.rowCount, assertNotNull(tabsProvider.singleTab(tab.name, webcil, context), tab.name).rowCount, tab.name)
        }
        assertEquals(tabsProvider.groupedTabs(webcil, context).map { it.name }, grouped)

        // An ordinary module advertises no .NET tabs.
        val plain = createBinaryProvider(File("../wasmParser/src/commonTest/resources/c/utf8-c.wasm").readBytes().toCommonByteBuffer(), "utf8-c.wasm")
        val (plainNames, plainGrouped) = assertNotNull(tabsProvider.availableTabNames(plain, context))
        assertTrue(".NET" !in plainNames && plainGrouped.isEmpty())
    }
}

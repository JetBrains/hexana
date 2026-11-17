package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.PdbBinaryProvider
import org.jetbrains.hexana.providers.PortablePdbBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `.pdb` tabs over `sample.pdb` (lld-link) and `sample-clr-portable.pdb` (Roslyn) in `parsing/native-fixtures/test-data/pe`. */
class PdbTableTabsTest {
    private fun bytes(name: String) = File("../parsing/native-fixtures/test-data/pe/$name").readBytes()

    private val tabs = PdbTableTabsProvider()

    private fun TableTabDescriptor.cell(row: Int, column: String) = columns.single { it.name == column }.cellProvider(row).representation
    private fun TableTabDescriptor.rowWhere(column: String, value: String) = (0 until rowCount).single { cell(it, column) == value }
    private fun TableTabDescriptor.value(field: String) = cell(rowWhere("Field", field), "Value")

    @Test
    fun `both PDB kinds are recognized by content, whatever the name`() {
        val windows = assertIs<PdbBinaryProvider>(createBinaryProvider(bytes("sample.pdb").toCommonByteBuffer(), "symbols.bin"))
        val portable = assertIs<PortablePdbBinaryProvider>(createBinaryProvider(bytes("sample-clr-portable.pdb").toCommonByteBuffer(), "symbols.bin"))
        assertEquals("Windows PDB", fileTypeDescription(windows))
        assertEquals("pdb", binaryFormatId(windows))
        assertEquals("Portable PDB", fileTypeDescription(portable))
        assertEquals("portable-pdb", binaryFormatId(portable))
        assertEquals("AEBD2BE3-8B00-6D1E-4C4C-44205044422E", windows.pdbGuid)
        assertEquals("AC392DA6-2C27-4521-A6B4-0D5948FC1F02", portable.pdbGuid)
    }

    @Test
    fun `windows PDB tabs`() {
        val provider = assertIs<PdbBinaryProvider>(createBinaryProvider(bytes("sample.pdb").toCommonByteBuffer(), "sample.pdb"))
        val context = TableTabsContext()
        val names = listOf("PDB", "Streams", "Modules", "Source Files", "Section Contributions")
        assertEquals(names to emptyList<String>(), tabs.availableTabNames(provider, context))
        assertEquals(names, tabs.tableTabs(provider).map { it.name })

        val summary = assertNotNull(tabs.singleTab("PDB", provider, context))
        assertEquals("AEBD2BE3-8B00-6D1E-4C4C-44205044422E", summary.value("GUID"))
        assertEquals("AMD64 (0x8664)", summary.value("Machine"))
        assertEquals("VC70 (20000404)", summary.value("PDB Version"))
        assertEquals("14.11", summary.value("Toolchain"))
        assertTrue((0 until summary.rowCount).none { summary.cell(it, "Field") == "Warning" })

        val streams = assertNotNull(tabs.singleTab("Streams", provider, context))
        assertEquals(15, streams.rowCount)
        assertEquals("DBI (debug info)", streams.cell(3, "Contents"))
        assertEquals("Module \"* Linker *\"", streams.cell(12, "Contents"))

        val modules = assertNotNull(tabs.singleTab("Modules", provider, context))
        assertEquals(listOf("/tmp/sample-5bae4d.o", "* Linker *"), (0 until modules.rowCount).map { modules.cell(it, "Module") })

        val files = assertNotNull(tabs.singleTab("Source Files", provider, context))
        assertEquals("/src/src/sample.c", files.cell(0, "File"))
        assertEquals(16, assertNotNull(tabs.singleTab("Section Contributions", provider, context)).rowCount)

        // Every stream run is a structure range pointing at the Streams tab.
        assertTrue(provider.structureRanges.any { it.label == "Stream 3: DBI (debug info)" && it.tabName == "Streams" })
    }

    @Test
    fun `portable PDB tabs`() {
        val provider = assertIs<PortablePdbBinaryProvider>(createBinaryProvider(bytes("sample-clr-portable.pdb").toCommonByteBuffer(), "sample-clr-portable.pdb"))
        val context = TableTabsContext()
        val names = listOf("Portable PDB", "Documents", "Streams", "Tables")
        assertEquals(names to emptyList<String>(), tabs.availableTabNames(provider, context))
        assertEquals(names, tabs.tableTabs(provider).map { it.name })

        val summary = assertNotNull(tabs.singleTab("Portable PDB", provider, context))
        assertEquals("PDB v1.0", summary.value("Metadata Version"))
        assertEquals("0x83123A8A", summary.value("PDB Id Stamp"))
        assertEquals("MethodDef 0x6000009", summary.value("Entry Point"))
        assertEquals("14", summary.value("Methods With Debug Info"))
        assertEquals("C#", summary.value("Languages"))

        val documents = assertNotNull(tabs.singleTab("Documents", provider, context))
        assertEquals("/src/clr/Sample.cs", documents.cell(0, "Name"))
        assertEquals("SHA-256", documents.cell(0, "Hash Algorithm"))

        val tables = assertNotNull(tabs.singleTab("Tables", provider, context))
        assertEquals("14", tables.cell(tables.rowWhere("Name", "MethodDebugInformation"), "Rows"))
        assertEquals(listOf("#Pdb", "#~", "#Strings", "#US", "#GUID", "#Blob"), provider.metadata.streams.map { it.name })
    }
}

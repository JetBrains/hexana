package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ClrResourcesBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.createBinaryProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `.resources` support over `parsing/native-fixtures/test-data/pe/sample.resources` and `sample-clr.exe`. */
class ClrResourcesTableTabsTest {
    private fun bytes(name: String) = File("../parsing/native-fixtures/test-data/pe/$name").readBytes()

    private val provider = assertIs<ClrResourcesBinaryProvider>(createBinaryProvider(bytes("sample.resources").toCommonByteBuffer(), "sample.resources"))
    private val tabs = ClrResourcesTableTabsProvider()

    private fun TableTabDescriptor.cell(row: Int, column: String) = columns.single { it.name == column }.cellProvider(row).representation
    private fun TableTabDescriptor.rowWhere(column: String, value: String) = (0 until rowCount).single { cell(it, column) == value }

    @Test
    fun `resource sets are recognized by their magic, whatever the name`() {
        assertIs<ClrResourcesBinaryProvider>(createBinaryProvider(bytes("sample.resources").toCommonByteBuffer(), "strings.bin"))
        assertEquals(".NET Resources", fileTypeDescription(provider))
        assertEquals("dotnet-resources", binaryFormatId(provider))
        assertTrue(provider.strings.any { it.value == "Hello from Hexana" })
        assertEquals(listOf("Header", "Names", "Data"), provider.structureRanges.map { it.label })
    }

    @Test
    fun `tabs list the header and every resource`() {
        val context = TableTabsContext()
        assertEquals(listOf("Header", "Resources") to emptyList<String>(), tabs.availableTabNames(provider, context))
        assertEquals(listOf("Header", "Resources"), tabs.tableTabs(provider).map { it.name })
        assertEquals(19, assertNotNull(tabs.singleTab("Resources", provider, context)).rowCount)

        val header = tabs.tableTabs(provider).single { it.name == "Header" }
        assertEquals("2", header.cell(header.rowWhere("Field", "Format Version"), "Value"))
        assertTrue(header.cell(header.rowWhere("Field", "Type 1"), "Value").startsWith("Margin, MakeResources"))

        val resources = tabs.tableTabs(provider).single { it.name == "Resources" }
        val multiline = resources.rowWhere("Name", "Multiline")
        assertEquals("line one\\nline two", resources.cell(multiline, "Value"))
        assertEquals("string", resources.cell(multiline, "Type"))
        assertEquals("Margin", resources.cell(resources.rowWhere("Name", "Margin"), "Type"))
        val pixel = resources.rowWhere("Name", "Pixel")
        assertEquals("12 bytes (PNG image)", resources.cell(pixel, "Value"))
        // Binary values select their payload, without the type code and length prefix.
        val payload = provider.parsedResources!!.resources.single { it.name == "Pixel" }.payloadRange!!
        val selected = assertNotNull(resources.rowSelectionProvider(pixel))
        assertEquals(payload.offset to payload.size, selected.offset to selected.size)
    }

    @Test
    fun `resource sets embedded in an assembly expand in its Resources tab`() {
        val assembly = assertIs<PeBinaryProvider>(createBinaryProvider(bytes("sample-clr.exe").toCommonByteBuffer(), "sample-clr.exe"))
        val group = NativeTableTabsProvider().groupedTabs(assembly).single { it.name == CLR_METADATA_GROUP_NAME }
        val resourcesTab = group.innerTabs.single { it.name == "Resources" }
        val strings = resourcesTab.rowWhere("Name", "Hexana.Sample.Strings.resources")
        val entries = assertNotNull(assertNotNull(resourcesTab.childrenProvider)(strings))
        assertEquals(19, entries.rowCount)
        assertEquals("Answer", entries.columns[0].cellProvider(0).representation)
        assertEquals("42", entries.columns[2].cellProvider(0).representation)
        assertNull(resourcesTab.childrenProvider!!(resourcesTab.rowWhere("Name", "Hexana.Sample.hello.txt")))
    }
}

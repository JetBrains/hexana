package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.ArscBinaryProvider
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArscTableTabsTest {

    @Test
    fun detectsResourceTableAsArscProvider() {
        val provider = nativeOrGenericBinaryProvider(buildSampleArsc().toCommonByteBuffer())
        assertIs<ArscBinaryProvider>(provider)
        assertEquals("Android Resource Table", fileTypeDescription(provider))
        assertEquals("arsc", binaryFormatId(provider))
        assertEquals(listOf("Hello world", "Bonjour"), provider.strings.map { it.value })
    }

    @Test
    fun registryProducesResourceTabs() {
        val provider = nativeOrGenericBinaryProvider(buildSampleArsc().toCommonByteBuffer())
        val tabs = TableTabsProviderRegistry.providers.flatMap { it.tableTabs(provider) }

        val header = tabs.single { it.name == "Header" }
        val headerRows = (0 until header.rowCount).map { row ->
            header.columns[0].cellProvider(row).representation to
                header.columns[1].cellProvider(row).representation
        }
        assertTrue("Packages" to "1" in headerRows, "unexpected header rows: $headerRows")
        assertTrue("Package 0x7f" to "com.example.app" in headerRows, "unexpected header rows: $headerRows")

        val resources = tabs.single { it.name == "Resources" }
        assertEquals(2, resources.rowCount)
        val rows = (0 until resources.rowCount).map { row ->
            resources.columns.map { it.cellProvider(row).representation }
        }
        assertEquals(
            listOf("0x7f020000", "string", "app_name", "default", "Hello world"),
            rows[0],
        )
        assertEquals(
            listOf("0x7f020000", "string", "app_name", "fr", "Bonjour"),
            rows[1],
        )
    }
}
